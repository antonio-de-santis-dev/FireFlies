package it.fireflies.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Riconciliazione del registro di occupazione con le prenotazioni (TEST_STRATEGY §3.4).
 * Viene eseguita dopo ogni test di integrazione: qualunque differenza è un errore. FIX 2: un quanto è coperto da una
 * prenotazione se l'intervallo la tocca anche in parte (orari degli eventi a qualunque minuto) e il budget di ogni
 * fascia coincide con le persone che lo consumano.
 */
@TestComponent
public class Reconciliation {

    private final JdbcClient jdbc;

    public Reconciliation(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void assertClean() {
        assertThat(count("""
                SELECT count(*) FROM capacity_slots s
                 WHERE s.occupied_covers <> COALESCE((
                       SELECT sum(r.guest_count) FROM reservations r
                        WHERE r.capacity_zone_id = s.zone_id
                          AND r.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
                          AND r.starts_at < s.slot_start + interval '15 minutes' AND r.occupied_until > s.slot_start), 0)
                """)).as("quanti in cui il registro differisce dalle prenotazioni").isZero();

        assertThat(count("SELECT count(*) FROM capacity_slots WHERE occupied_covers > capacity_covers"))
                .as("quanti oltre la capienza").isZero();

        assertThat(count("""
                SELECT count(*) FROM capacity_slots s
                 WHERE s.exclusive_event_id IS NOT NULL AND EXISTS (
                       SELECT 1 FROM reservations r
                        WHERE r.capacity_zone_id = s.zone_id AND r.event_id IS NULL
                          AND r.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
                          AND r.starts_at < s.slot_start + interval '15 minutes' AND r.occupied_until > s.slot_start)
                """)).as("quanti di eventi esclusivi con prenotazioni ordinarie").isZero();

        assertThat(count("""
                SELECT count(*) FROM events e
                 WHERE e.confirmed_covers <> COALESCE((
                       SELECT sum(r.guest_count) FROM reservations r
                        WHERE r.event_id = e.id AND r.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')), 0)
                """)).as("eventi con quota incoerente").isZero();

        // FIX 2: budget per giorno e fascia = persone confermate, completate e non presentate (nessun doppio conteggio)
        assertThat(count("""
                SELECT count(*) FROM band_capacity_usage u
                  FULL JOIN (SELECT reservation_date, service_band, sum(guest_count) AS covers FROM reservations
                              WHERE status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW') GROUP BY reservation_date, service_band) r
                    ON r.reservation_date = u.service_date AND r.service_band = u.band
                 WHERE COALESCE(u.confirmed_covers, 0) <> COALESCE(r.covers, 0)
                """)).as("fasce con budget incoerente con le prenotazioni").isZero();

        assertThat(count("SELECT count(*) FROM reservations WHERE service_band IS DISTINCT FROM fireflies_band(starts_at, service_window_id)"))
                .as("prenotazioni con fascia incoerente").isZero();

        assertThat(count("""
                SELECT count(*) FROM reservation_table_assignments a
                  JOIN reservations r ON r.id = a.reservation_id
                 WHERE a.active AND (r.status <> 'CONFIRMED'
                       OR a.occupied <> tstzrange(r.starts_at, r.occupied_until, '[)'))
                """)).as("assegnazioni tavolo incoerenti con la prenotazione").isZero();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
