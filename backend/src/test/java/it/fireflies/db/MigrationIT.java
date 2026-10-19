package it.fireflies.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import it.fireflies.support.PostgresIntegrationTest;

/** Compatibilità PostgreSQL/Flyway, migrazioni da database vuoto, vincoli presenti, dati iniziali non demo. */
class MigrationIT extends PostgresIntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Test
    void postgresql17ConBtreeGist() {
        int version = jdbc.sql("SHOW server_version_num").query(Integer.class).single();
        assertThat(version).isBetween(170000, 179999);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void tutteLeMigrazioniApplicateEValide() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion()))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(applied).allMatch(m -> m.getState().isApplied() && !m.getState().isFailed());
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void migrazioneDaUnDatabaseCompletamenteVuoto() {
        String baseUrl = ((HikariDataSource) dataSource).getJdbcUrl();
        String user = ((HikariDataSource) dataSource).getUsername();
        String password = ((HikariDataSource) dataSource).getPassword();
        jdbc.sql("DROP DATABASE IF EXISTS fireflies_vuoto").update();
        jdbc.sql("CREATE DATABASE fireflies_vuoto").update();
        try {
            DriverManagerDataSource empty = new DriverManagerDataSource(
                    baseUrl.replaceFirst("/[^/?]+(\\?|$)", "/fireflies_vuoto$1"), user, password);
            Flyway fresh = Flyway.configure().dataSource(empty).locations("classpath:db/migration")
                    .cleanDisabled(true).load();
            MigrateResult result = fresh.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(7);
            assertThat(fresh.migrate().migrationsExecuted).as("seconda esecuzione: niente da fare").isZero();
            assertThat(fresh.validateWithResult().validationSuccessful).isTrue();
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS fireflies_vuoto WITH (FORCE)").update();
        }
    }

    @Test
    void vincoliDelleInvariantiPresenti() {
        List<String> constraints = jdbc.sql("SELECT conname FROM pg_constraint").query(String.class).list();
        assertThat(constraints).contains(
                "ck_slot_not_overbooked", "ck_slot_aligned", "ex_table_no_overlap", "ck_override_complete",
                "ck_event_quota", "ck_res_occupying", "ck_res_aligned", "ck_res_occupied_until",
                "ck_policy_manual_only", "ck_outbox_lease", "ux_outbox_business_key", "ux_res_idempotency_key");
        List<String> indexes = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                .query(String.class).list();
        assertThat(indexes).contains("ux_res_web_duplicate", "ix_res_zone_time", "ix_outbox_ready", "ux_assignment_once");
    }

    @Test
    void datiInizialiSoloConfermatiOSpenti() {
        assertThat(count("SELECT count(*) FROM allergens")).isEqualTo(14);
        assertThat(count("SELECT count(*) FROM site_settings WHERE sms_enabled")).isZero();
        assertThat(count("SELECT count(*) FROM booking_policies WHERE web_booking_enabled")).isZero();
        assertThat(count("SELECT count(*) FROM booking_policies WHERE confirmation_mode <> 'MANUAL'")).isZero();
        assertThat(count("SELECT count(*) FROM capacity_zones WHERE name = 'Locale' AND NOT active AND physical_capacity = 0"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_templates")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM notification_templates WHERE active")).as("testi da approvare").isZero();
        assertThat(count("SELECT count(*) FROM app_users")).as("nessun account creato dalle migrazioni").isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
