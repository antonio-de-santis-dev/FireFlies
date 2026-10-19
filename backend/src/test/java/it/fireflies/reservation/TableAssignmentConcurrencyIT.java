package it.fireflies.reservation;

import static it.fireflies.support.BookingFixtures.at;
import static it.fireflies.support.BookingFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;

import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;
import it.fireflies.support.PostgresIntegrationTest;

/**
 * C-17: due gestori assegnano lo stesso tavolo a prenotazioni sovrapposte nello stesso istante.
 *
 * <p>Scoperto in FASE 2: due INSERT concorrenti controllati dal vincolo di esclusione possono andare in deadlock
 * (ognuno attende la transazione dell'altro per verificare il conflitto). Il vincolo impedisce comunque la doppia
 * assegnazione, ma l'errore è un deadlock dopo {@code deadlock_timeout}. Procedura adottata: bloccare prima le righe
 * dei tavoli in ordine di id ({@code FOR NO KEY UPDATE}); il secondo gestore attende e riceve un conflitto pulito.
 */
class TableAssignmentConcurrencyIT extends PostgresIntegrationTest {

    private UUID table;
    private UUID first;
    private UUID second;

    private void setUpTwoOverlappingBookings() {
        UUID zone = fixtures.zone("Sala", 20);
        UUID window = fixtures.window(zone);
        UUID area = jdbc.sql("INSERT INTO dining_areas (zone_id, name) VALUES (?, 'Sala interna') RETURNING id")
                .param(zone).query(UUID.class).single();
        table = jdbc.sql("INSERT INTO dining_tables (area_id, code, min_seats, max_seats) VALUES (?, 'T4', 2, 4) RETURNING id")
                .param(area).query(UUID.class).single();
        first = fixtures.confirm(zone, window, at("19:00"), Duration.ofHours(2), 4);
        second = fixtures.confirm(zone, window, at("20:00"), Duration.ofHours(2), 4);
    }

    /** Procedura di FASE 5: lock ordinato dei tavoli, poi inserimento → una sola assegnazione, errore 23P01 pulito. */
    @RepeatedTest(20)
    void conLockDelTavoloUnaSolaAssegnazioneEConflittoPulito() {
        setUpTwoOverlappingBookings();
        List<Result<Integer>> results = Concurrency.run(List.of(
                () -> assignWithTableLock(first, "19:00", "21:00"),
                () -> assignWithTableLock(second, "20:00", "22:00")));

        assertThat(results).filteredOn(Result::ok).hasSize(1);
        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(Concurrency.hasSqlState(r.error(), "23P01")).isTrue());
        assertThat(activeAssignments()).isEqualTo(1);
    }

    /** Anche senza lock (ultima difesa) il database non permette mai due assegnazioni sovrapposte. */
    @RepeatedTest(10)
    void senzaLockIlVincoloImpedisceComunqueLaDoppiaAssegnazione() {
        setUpTwoOverlappingBookings();
        List<Result<Integer>> results = Concurrency.run(List.of(
                () -> insert(first, "19:00", "21:00"),
                () -> insert(second, "20:00", "22:00")));

        assertThat(results).filteredOn(Result::ok).hasSize(1);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(Concurrency.hasSqlState(r.error(), "23P01") || r.deadlock()).isTrue());
        assertThat(activeAssignments()).isEqualTo(1);
    }

    private int assignWithTableLock(UUID reservation, String from, String to) {
        return tx.execute(status -> {
            jdbc.sql("SELECT id FROM dining_tables WHERE id = ANY(?) ORDER BY id FOR NO KEY UPDATE")
                    .param(new UUID[] {table}).query(UUID.class).list();
            return jdbc.sql("INSERT INTO reservation_table_assignments (reservation_id, table_id, occupied) VALUES (?, ?, tstzrange(?, ?, '[)'))")
                    .params(reservation, table, utc(at(from)), utc(at(to))).update();
        });
    }

    private int insert(UUID reservation, String from, String to) {
        return tx.execute(status -> jdbc.sql(
                "INSERT INTO reservation_table_assignments (reservation_id, table_id, occupied) VALUES (?, ?, tstzrange(?, ?, '[)'))")
                .params(reservation, table, utc(at(from)), utc(at(to))).update());
    }

    private long activeAssignments() {
        return jdbc.sql("SELECT count(*) FROM reservation_table_assignments WHERE active").query(Long.class).single();
    }
}
