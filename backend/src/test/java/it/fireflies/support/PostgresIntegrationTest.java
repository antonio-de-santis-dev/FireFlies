package it.fireflies.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import it.fireflies.security.LoginAttemptLimiter;

/**
 * Base dei test di integrazione: applicazione completa, profilo {@code test}, migrazioni Flyway applicate
 * su PostgreSQL 17 reale. Dopo ogni test i dati operativi vengono svuotati e la riconciliazione deve risultare pulita.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainer.class, Reconciliation.class, BookingFixtures.class, MediaTestStorage.class})
public abstract class PostgresIntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    protected Reconciliation reconciliation;

    @Autowired
    protected BookingFixtures fixtures;

    @Autowired
    protected LoginAttemptLimiter loginLimiter;

    @Autowired
    protected SwitchableMediaStorage mediaStorage;

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
            // contenuti e menu prima delle foto: le chiavi esterne verso media_assets sono ON DELETE RESTRICT
            jdbc.sql("""
                    TRUNCATE menu_item_allergens, menu_items, menu_categories, hero_slides, owners, staff_members,
                             announcements, opening_hours, opening_exceptions, media_orphans
                    """).update();
            jdbc.sql("""
                    UPDATE site_content SET hero_claim = NULL, hero_lead = NULL, hero_media_id = NULL, story_title = NULL,
                           story_text = NULL, story_confirmed = false, owners_confirmed = false, staff_confirmed = false
                    """).update();
            jdbc.sql("UPDATE site_sections SET enabled = true").update();
            jdbc.sql("UPDATE site_settings SET demo_content = false, phone_public = NULL, email_public = NULL").update();
            jdbc.sql("DELETE FROM media_assets").update();
            mediaStorage.reset();
            jdbc.sql("TRUNCATE spring_session CASCADE").update();
            jdbc.sql("DELETE FROM audit_logs").update();
            jdbc.sql("DELETE FROM app_users").update();
            loginLimiter.clear();
        }
    }
}
