package it.fireflies.capacity;

import static it.fireflies.support.BookingFixtures.at;
import static it.fireflies.support.BookingFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import it.fireflies.capacity.CapacityExceptions.CapacityBelowConfirmedException;
import it.fireflies.common.ApiException;
import it.fireflies.support.PostgresIntegrationTest;

/** Registro di occupazione su PostgreSQL reale: capienza, rilasci, modifiche, cambi di capienza (BOOKING_RULES §3). */
class CapacityLedgerIT extends PostgresIntegrationTest {

    private static final Duration TWO_HOURS = Duration.ofHours(2);

    @Autowired
    CapacityLedger ledger;

    private static String code(Throwable e) {
        return ((ApiException) e).code();
    }

    @Test
    void casoGuidaSovrapposizioneTraFasceDiverse() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);

        fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6);
        assertThatThrownBy(() -> fixtures.confirm(zone, window, at("20:00"), TWO_HOURS, 6))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo("CAPACITY_EXCEEDED");
                    assertThat(((ApiException) e).context()).containsEntry("firstFullSlot", at("20:00").toString())
                            .containsEntry("remainingCovers", 4);
                });
        fixtures.confirm(zone, window, at("21:00"), TWO_HOURS, 6); // adiacente: non si sovrappone

        assertThat(fixtures.confirmedCount()).as("la prenotazione rifiutata non resta salvata").isEqualTo(2);
        assertThat(fixtures.occupiedAt(zone, at("20:45"))).isEqualTo(6);
        assertThat(fixtures.occupiedAt(zone, at("21:00"))).isEqualTo(6);
    }

    @Test
    void capienzaPienaMaiSuperata() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 10);
        assertThatThrownBy(() -> fixtures.confirm(zone, window, at("20:45"), Duration.ofMinutes(30), 1))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CAPACITY_EXCEEDED"));
    }

    @Test
    void annullamentoLiberaIPosti() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID first = fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 10);
        fixtures.cancel(first);
        assertThat(fixtures.occupiedAt(zone, at("19:00"))).isZero();
        fixtures.confirm(zone, window, at("19:30"), TWO_HOURS, 10);
    }

    @Test
    void spostamentoEModificaPersoneCoerenti() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID booking = fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6);

        fixtures.change(booking, zone, at("20:00"), TWO_HOURS, 6);
        assertThat(fixtures.occupiedAt(zone, at("19:00"))).isZero();
        assertThat(fixtures.occupiedAt(zone, at("21:45"))).isEqualTo(6);

        fixtures.change(booking, zone, at("20:00"), TWO_HOURS, 9);
        assertThat(fixtures.occupiedAt(zone, at("20:00"))).isEqualTo(9);

        fixtures.change(booking, zone, at("20:00"), Duration.ofHours(3), 4);
        assertThat(fixtures.occupiedAt(zone, at("22:45"))).isEqualTo(4);
    }

    @Test
    void modificaCheNonStaNonCambiaNulla() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID booking = fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6);
        fixtures.confirm(zone, window, at("21:00"), TWO_HOURS, 6);

        assertThatThrownBy(() -> fixtures.change(booking, zone, at("19:00"), Duration.ofHours(3), 6))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CAPACITY_EXCEEDED"));
        Integer guests = jdbc.sql("SELECT guest_count FROM reservations WHERE id = ?").param(booking).query(Integer.class).single();
        assertThat(guests).isEqualTo(6);
        assertThat(fixtures.occupiedAt(zone, at("20:45"))).isEqualTo(6);
        assertThat(fixtures.occupiedAt(zone, at("21:00"))).isEqualTo(6);
    }

    @Test
    void cambioDiZona() {
        UUID sala = fixtures.zone("Sala", 10);
        UUID dehors = fixtures.zone("Dehors", 8);
        UUID window = fixtures.window(sala);
        UUID booking = fixtures.confirm(sala, window, at("19:00"), TWO_HOURS, 6);
        fixtures.change(booking, dehors, at("19:00"), TWO_HOURS, 6);
        assertThat(fixtures.occupiedAt(sala, at("19:00"))).isZero();
        assertThat(fixtures.occupiedAt(dehors, at("19:00"))).isEqualTo(6);
    }

    @Test
    void uscitaAnticipataLiberaSoloIQuantiFuturi() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID booking = fixtures.confirm(zone, window, at("19:00"), Duration.ofHours(3), 10);
        fixtures.completeEarly(booking, at("20:30"));
        assertThat(fixtures.occupiedAt(zone, at("20:15"))).isEqualTo(10);
        assertThat(fixtures.occupiedAt(zone, at("20:30"))).isZero();
        fixtures.confirm(zone, window, at("20:30"), Duration.ofMinutes(90), 10);
    }

    @Test
    void capienzaSottoIConfermatiRifiutataSenzaToccareNulla() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 8);

        assertThatThrownBy(() -> fixtures.changeZoneCapacity(zone, 6))
                .isInstanceOf(CapacityBelowConfirmedException.class)
                .satisfies(e -> assertThat(((CapacityBelowConfirmedException) e).conflicts()).hasSize(8));
        assertThat(physical(zone)).isEqualTo(10);
        assertThat(capacityAt(zone, "19:00")).isEqualTo(10);

        fixtures.changeZoneCapacity(zone, 8);
        assertThat(physical(zone)).isEqualTo(8);
        assertThat(capacityAt(zone, "19:00")).isEqualTo(8);
        assertThatThrownBy(() -> fixtures.confirm(zone, window, at("20:00"), TWO_HOURS, 1))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CAPACITY_EXCEEDED"));
    }

    @Test
    void quantiDiUnEventoEsclusivoBloccanoLeOrdinarie() {
        UUID zone = fixtures.zone("Sala", 50);
        UUID window = fixtures.window(zone);
        UUID event = jdbc.sql("""
                INSERT INTO events (slug, title, kind, status, starts_at, ends_at, zone_id, exclusive)
                VALUES ('serata-prova', 'Serata di prova', 'SERATA', 'PUBLISHED', ?, ?, ?, true) RETURNING id
                """).params(utc(at("22:00")), utc(at("23:45")), zone).query(UUID.class).single();
        jdbc.sql("INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers, exclusive_event_id) VALUES (?, ?, 50, ?)")
                .params(zone, utc(at("22:00")), event).update();

        assertThatThrownBy(() -> fixtures.confirm(zone, window, at("21:00"), TWO_HOURS, 2))
                .satisfies(e -> assertThat(code(e)).isEqualTo("EVENT_BLOCKS_SLOT"));
        fixtures.confirm(zone, window, at("20:00"), TWO_HOURS, 2); // finisce alle 22:00: nessun conflitto
    }

    @Test
    void zonaNonAttivaNonAccettaOccupazioni() {
        UUID locale = jdbc.sql("SELECT id FROM capacity_zones WHERE name = 'Locale'").query(UUID.class).single();
        UUID window = fixtures.window(locale);
        assertThatThrownBy(() -> fixtures.confirm(locale, window, at("19:00"), TWO_HOURS, 2))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ZONE_INACTIVE"));
    }

    @Test
    void ilRegistroSiUsaSoloDentroUnaTransazione() {
        UUID zone = fixtures.zone("Sala", 10);
        assertThatThrownBy(() -> ledger.occupy(new Occupancy(zone, OccupancyInterval.of(at("19:00"), TWO_HOURS), 2)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private int physical(UUID zone) {
        return jdbc.sql("SELECT physical_capacity FROM capacity_zones WHERE id = ?").param(zone).query(Integer.class).single();
    }

    private int capacityAt(UUID zone, String time) {
        return jdbc.sql("SELECT capacity_covers FROM capacity_slots WHERE zone_id = ? AND slot_start = ?")
                .params(zone, utc(at(time))).query(Integer.class).single();
    }
}
