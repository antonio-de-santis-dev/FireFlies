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
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
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
            assertThat(result.migrationsExecuted).isEqualTo(14);
            assertThat(fresh.migrate().migrationsExecuted).as("seconda esecuzione: niente da fare").isZero();
            assertThat(fresh.validateWithResult().validationSuccessful).isTrue();
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS fireflies_vuoto WITH (FORCE)").update();
        }
    }

    /**
     * FIX-1: V12 su un database alla V11 con la vecchia configurazione (limiti per fascia e per data, quanti con capienza
     * ridotta, chiusura di un giorno): i dati restano coerenti e nessuna prenotazione viene toccata.
     */
    @Test
    void v12DaUnDatabaseConLimitiPerFasciaEPerData() {
        String baseUrl = ((HikariDataSource) dataSource).getJdbcUrl();
        String user = ((HikariDataSource) dataSource).getUsername();
        String password = ((HikariDataSource) dataSource).getPassword();
        jdbc.sql("DROP DATABASE IF EXISTS fireflies_v11").update();
        jdbc.sql("CREATE DATABASE fireflies_v11").update();
        try {
            DriverManagerDataSource db = new DriverManagerDataSource(
                    baseUrl.replaceFirst("/[^/?]+(\\?|$)", "/fireflies_v11$1"), user, password);
            Flyway.configure().dataSource(db).locations("classpath:db/migration").target("11").load().migrate();
            org.springframework.jdbc.core.simple.JdbcClient old = org.springframework.jdbc.core.simple.JdbcClient.create(db);
            old.sql("UPDATE capacity_zones SET physical_capacity = 40, active = true WHERE name = 'Locale'").update();
            java.util.UUID zone = old.sql("SELECT id FROM capacity_zones WHERE name = 'Locale'").query(java.util.UUID.class).single();
            old.sql("""
                    INSERT INTO service_windows (zone_id, iso_day, name, first_start, last_start, slot_minutes, default_duration_minutes,
                                                 max_covers, valid_from)
                    VALUES (?, 1, 'Colazione', '07:30', '10:30', 30, 60, 20, DATE '2026-01-01')
                    """).param(zone).update();
            old.sql("INSERT INTO booking_exceptions (service_date, kind, capacity_override) VALUES (DATE '2031-03-03', 'CAPACITY_OVERRIDE', 10)").update();
            old.sql("INSERT INTO booking_exceptions (service_date, kind) VALUES (DATE '2031-03-04', 'CLOSED')").update();
            old.sql("INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers, occupied_covers) VALUES (?, TIMESTAMPTZ '2031-03-03 08:00+01', 10, 6)")
                    .param(zone).update();
            old.sql("INSERT INTO opening_exceptions (day, closed) VALUES (DATE '2031-12-25', true)").update();

            MigrateResult result = Flyway.configure().dataSource(db).locations("classpath:db/migration").target("12").load().migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(old.sql("SELECT capacity_covers FROM capacity_slots").query(Integer.class).single())
                    .as("quanto riallineato alla capienza del locale").isEqualTo(40);
            assertThat(old.sql("SELECT occupied_covers FROM capacity_slots").query(Integer.class).single()).as("confermati intatti").isEqualTo(6);
            assertThat(old.sql("SELECT kind FROM booking_exceptions").query(String.class).list()).containsExactly("CLOSED");
            assertThat(old.sql("SELECT end_day FROM opening_exceptions").query(java.time.LocalDate.class).single())
                    .isEqualTo(java.time.LocalDate.of(2031, 12, 25));
            assertThat(old.sql("SELECT count(*) FROM information_schema.columns WHERE column_name IN ('max_covers', 'max_party_size_web', 'capacity_override')")
                    .query(Integer.class).single()).as("nessuna impostazione fantasma").isZero();
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS fireflies_v11 WITH (FORCE)").update();
        }
    }

    /**
     * FIX 2: la V13 su un database V12 con prenotazioni reali di ogni stato. Il budget per fascia nasce solo dalle persone
     * confermate, completate e non presentate; nessuna riga viene cancellata o spostata; i duplicati attivi già presenti
     * restano com'erano (fuori dal nuovo vincolo), quelli nuovi sono bloccati dal database.
     */
    @Test
    void v13DaUnDatabaseV12ConPrenotazioniEsistenti() {
        String baseUrl = ((HikariDataSource) dataSource).getJdbcUrl();
        String user = ((HikariDataSource) dataSource).getUsername();
        String password = ((HikariDataSource) dataSource).getPassword();
        jdbc.sql("DROP DATABASE IF EXISTS fireflies_v12 WITH (FORCE)").update();
        jdbc.sql("CREATE DATABASE fireflies_v12").update();
        try {
            DriverManagerDataSource db = new DriverManagerDataSource(
                    baseUrl.replaceFirst("/[^/?]+(\\?|$)", "/fireflies_v12$1"), user, password);
            Flyway.configure().dataSource(db).locations("classpath:db/migration").target("12").load().migrate();
            org.springframework.jdbc.core.simple.JdbcClient old = org.springframework.jdbc.core.simple.JdbcClient.create(db);
            old.sql("UPDATE capacity_zones SET physical_capacity = 40, active = true WHERE name = 'Locale'").update();
            java.util.UUID zone = old.sql("SELECT id FROM capacity_zones WHERE name = 'Locale'").query(java.util.UUID.class).single();
            java.util.UUID window = old.sql("""
                    INSERT INTO service_windows (zone_id, iso_day, name, first_start, last_start, slot_minutes, default_duration_minutes, valid_from)
                    VALUES (?, 1, 'Aperitivo', '18:00', '21:00', 30, 120, DATE '2026-01-01') RETURNING id
                    """).param(zone).query(java.util.UUID.class).single();
            String insert = """
                    INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, occupied_until, guest_count,
                                              service_window_id, capacity_zone_id, customer_name, customer_phone_e164,
                                              privacy_ack_at, privacy_notice_version)
                    VALUES (?, ?, ?, DATE '2031-03-03', TIMESTAMPTZ '2031-03-03 19:00+01' + ?::interval,
                            TIMESTAMPTZ '2031-03-03 21:00+01' + ?::interval, ?, ?, ?, ?, 'Cliente di prova', ?, now(), 'v1')
                    """;
            Object[][] rows = {
                {"FF-M13A01", "CONFIRMED", "PHONE", "0 minutes", 4, "+390000000101"},
                {"FF-M13A02", "COMPLETED", "PHONE", "0 minutes", 2, "+390000000102"},
                {"FF-M13A03", "NO_SHOW", "PHONE", "30 minutes", 3, "+390000000103"},
                {"FF-M13A04", "CANCELLED", "PHONE", "0 minutes", 5, "+390000000104"},
                {"FF-M13A05", "REJECTED", "WEB", "0 minutes", 6, "+390000000105"},
                {"FF-M13A06", "PENDING", "WEB", "0 minutes", 2, "+390000000106"},
                {"FF-M13A07", "PENDING", "WEB", "60 minutes", 2, "+390000000106"}, // duplicato già presente
            };
            for (Object[] r : rows) {
                boolean occupying = List.of("CONFIRMED", "COMPLETED", "NO_SHOW").contains(r[1]);
                old.sql(insert).params(r[0], r[1], r[2], r[3], r[3],
                        occupying ? java.time.OffsetDateTime.parse("2031-03-03T20:00:00Z").plusMinutes(r[3].equals("0 minutes") ? 0 : r[3].equals("30 minutes") ? 30 : 60) : null,
                        r[4], window, zone, r[5]).update();
            }
            java.util.UUID event = old.sql("""
                    INSERT INTO events (slug, title, kind, status, starts_at, ends_at, exclusive, booking_mode)
                    VALUES ('serata-v12', 'Serata V12', 'SERATA', 'DRAFT', TIMESTAMPTZ '2031-03-04 21:00+01', TIMESTAMPTZ '2031-03-05 01:00+01', true, 'NONE')
                    RETURNING id
                    """).query(java.util.UUID.class).single();

            MigrateResult result = Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).as("V13 e V14").isEqualTo(2);
            assertThat(old.sql("SELECT count(*) FROM reservations").query(Integer.class).single()).as("nessuna riga cancellata").isEqualTo(7);
            // V14: nessun consenso WhatsApp presunto per le prenotazioni già esistenti
            assertThat(old.sql("SELECT count(*) FROM reservations WHERE whatsapp_consent_at IS NOT NULL OR whatsapp_consent_version IS NOT NULL")
                    .query(Integer.class).single()).isZero();
            assertThat(old.sql("SELECT active FROM notification_templates WHERE code = 'RESERVATION_CONFIRMED' AND channel = 'WHATSAPP'")
                    .query(Boolean.class).single()).isFalse();
            assertThat(old.sql("SELECT string_agg(status, ',' ORDER BY public_ref) FROM reservations").query(String.class).single())
                    .isEqualTo("CONFIRMED,COMPLETED,NO_SHOW,CANCELLED,REJECTED,PENDING,PENDING");
            assertThat(old.sql("SELECT count(*) FROM reservations WHERE service_band <> 'APERITIVO'").query(Integer.class).single()).isZero();
            assertThat(old.sql("SELECT confirmed_covers FROM band_capacity_usage WHERE service_date = DATE '2031-03-03' AND band = 'APERITIVO'")
                    .query(Integer.class).single()).as("4 confermate + 2 completate + 3 non presentate").isEqualTo(9);
            assertThat(old.sql("SELECT count(*) FROM band_capacity_usage").query(Integer.class).single()).isEqualTo(1);
            assertThat(old.sql("SELECT count(*) FROM reservations WHERE NOT dedup_enforced").query(Integer.class).single())
                    .as("i due duplicati già presenti restano, fuori dal vincolo").isEqualTo(2);
            assertThat(old.sql("SELECT starts_at = TIMESTAMPTZ '2031-03-04 21:00+01' FROM events WHERE id = ?").param(event)
                    .query(Boolean.class).single()).as("orari degli eventi intatti").isTrue();

            // dopo la V13: annullare una confermata restituisce il budget, una nuova richiesta duplicata è rifiutata dal database
            old.sql("UPDATE reservations SET status = 'CANCELLED', occupied_until = NULL WHERE public_ref = 'FF-M13A01'").update();
            assertThat(old.sql("SELECT confirmed_covers FROM band_capacity_usage").query(Integer.class).single()).isEqualTo(5);
            old.sql(insert).params("FF-M13A08", "PENDING", "WEB", "90 minutes", "90 minutes", null, 2, window, zone, "+390000000105")
                    .update(); // la precedente di quel telefono è rifiutata: si può chiedere di nuovo
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> old.sql(insert).params("FF-M13A09", "PENDING", "WEB",
                    "30 minutes", "30 minutes", null, 2, window, zone, "+390000000105").update())
                    .as("seconda richiesta attiva, stesso telefono, giorno e fascia, altro orario")
                    .hasMessageContaining("ux_res_web_band_duplicate");
        } finally {
            jdbc.sql("DROP DATABASE IF EXISTS fireflies_v12 WITH (FORCE)").update();
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
        assertThat(count("SELECT count(*) FROM notification_templates")).isEqualTo(13); // V14: conferma WhatsApp
        assertThat(count("SELECT count(*) FROM notification_templates WHERE channel = 'WHATSAPP'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_templates WHERE active")).as("testi da approvare").isZero();
        assertThat(count("SELECT count(*) FROM app_users")).as("nessun account creato dalle migrazioni").isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
