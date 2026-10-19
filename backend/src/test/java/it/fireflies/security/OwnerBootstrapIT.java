package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import it.fireflies.FirefliesApplication;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;
import it.fireflies.support.SecurityIntegrationTest;

/** Creazione del primo titolare con la procedura amministrativa. */
class OwnerBootstrapIT extends SecurityIntegrationTest {

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    DataSource dataSource;

    @Test
    void creaIlPrimoTitolareUnaVoltaSola() {
        String password = randomPassword();
        UUID id = bootstrap.createFirstOwner(" Titolare@Example.invalid ", "Titolare", password);

        AppUser owner = accounts.list().getFirst();
        assertThat(owner.id()).isEqualTo(id);
        assertThat(owner.role()).isEqualTo(Role.OWNER);
        assertThat(owner.email()).isEqualTo("titolare@example.invalid");
        assertThat(audit("OWNER_BOOTSTRAPPED")).isEqualTo(1);
        assertThat(browser().login("titolare@example.invalid", password).status()).isEqualTo(200);

        assertThatThrownBy(() -> bootstrap.createFirstOwner("altro@example.invalid", "Altro", randomPassword()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Esiste già un titolare");
        assertThat(accounts.list()).hasSize(1);
    }

    @Test
    void datiNonValidiRifiutati() {
        assertThatThrownBy(() -> bootstrap.createFirstOwner("non-una-email", "Titolare", randomPassword()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.createFirstOwner("titolare@example.invalid", "Titolare", "corta"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("almeno 12");
        assertThat(accounts.list()).isEmpty();
    }

    @Test
    void dueProcedureInsiemeCreanoUnSoloTitolare() {
        List<Result<UUID>> results = Concurrency.run(List.of(
                () -> bootstrap.createFirstOwner("uno@example.invalid", "Uno", randomPassword()),
                () -> bootstrap.createFirstOwner("due@example.invalid", "Due", randomPassword())));
        assertThat(results).filteredOn(Result::ok).hasSize(1);
        assertThat(accounts.list()).hasSize(1);
    }

    /** La procedura vera: applicazione senza web, argomenti da riga di comando, password da variabile. */
    @Test
    void proceduraDaRigaDiComando() {
        String password = randomPassword();
        System.setProperty("FIREFLIES_BOOTSTRAP_PASSWORD", password);
        try (ConfigurableApplicationContext ignored = run(
                "--fireflies.bootstrap.email=titolare@example.invalid", "--fireflies.bootstrap.display-name=Titolare")) {
            assertThat(accounts.list()).singleElement().satisfies(u -> assertThat(u.role()).isEqualTo(Role.OWNER));
        } finally {
            System.clearProperty("FIREFLIES_BOOTSTRAP_PASSWORD");
        }
        assertThat(browser().login("titolare@example.invalid", password).status()).isEqualTo(200);

        System.setProperty("FIREFLIES_BOOTSTRAP_PASSWORD", randomPassword());
        try {
            assertThatThrownBy(() -> run("--fireflies.bootstrap.email=secondo@example.invalid",
                    "--fireflies.bootstrap.display-name=Secondo"))
                    .hasStackTraceContaining("Esiste già un titolare");
        } finally {
            System.clearProperty("FIREFLIES_BOOTSTRAP_PASSWORD");
        }
    }

    @Test
    void passwordComeArgomentoRifiutata() {
        assertThatThrownBy(() -> run("--fireflies.bootstrap.email=titolare@example.invalid",
                "--fireflies.bootstrap.display-name=Titolare", "--FIREFLIES_BOOTSTRAP_PASSWORD=" + randomPassword()))
                .hasStackTraceContaining("Non passare la password come argomento");
        assertThat(accounts.list()).isEmpty();
    }

    private ConfigurableApplicationContext run(String... extra) {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        List<String> args = new java.util.ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + hikari.getJdbcUrl(),
                "--spring.datasource.username=" + hikari.getUsername(),
                "--spring.datasource.password=" + hikari.getPassword(),
                "--fireflies.bootstrap.create-owner=true",
                "--fireflies.bootstrap.exit=false"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(FirefliesApplication.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }
}
