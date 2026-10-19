package it.fireflies.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base dei test di integrazione: applicazione completa, profilo {@code test}, migrazioni Flyway applicate
 * su PostgreSQL 17 reale. Dopo ogni test i dati operativi vengono svuotati e la riconciliazione deve risultare pulita.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestcontainer.class, Reconciliation.class, BookingFixtures.class})
public abstract class PostgresIntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    protected Reconciliation reconciliation;

    @Autowired
    protected BookingFixtures fixtures;

    @AfterEach
    void verifyInvariantsAndClean() {
        try {
            reconciliation.assertClean();
        } finally {
            jdbc.sql("""
                    TRUNCATE reservation_table_assignments, reservation_status_history, reservations,
                             notification_attempts, notification_outbox, capacity_slots, booking_exceptions,
                             event_media, events, dining_tables, dining_areas, service_windows
                    """).update();
            jdbc.sql("DELETE FROM capacity_zones WHERE name <> 'Locale'").update();
            jdbc.sql("DELETE FROM media_assets").update();
            jdbc.sql("DELETE FROM app_users").update();
        }
    }
}
