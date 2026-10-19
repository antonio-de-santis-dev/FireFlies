package it.fireflies.capacity;

import static it.fireflies.support.BookingFixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import it.fireflies.common.ApiException;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;
import it.fireflies.support.PostgresIntegrationTest;

/**
 * Gare reali tra transazioni su PostgreSQL (TEST_STRATEGY §3.1). Ogni scenario è ripetuto; dopo ognuno la
 * riconciliazione (in {@link PostgresIntegrationTest}) verifica che registro e prenotazioni coincidano.
 */
class CapacityConcurrencyIT extends PostgresIntegrationTest {

    private static final Duration TWO_HOURS = Duration.ofHours(2);

    private static <T> long ok(List<Result<T>> results) {
        return results.stream().filter(Result::ok).count();
    }

    private static <T> void assertOnlyExpectedFailures(List<Result<T>> results, String... codes) {
        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(r.error()).isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).code()).isIn((Object[]) codes));
    }

    /** C-2: due conferme sovrapposte che insieme superano la capienza → una sola. */
    @RepeatedTest(20)
    void dueConfermeSovrapposteUnaSola() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        List<Result<UUID>> results = Concurrency.run(List.of(
                () -> fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6),
                () -> fixtures.confirm(zone, window, at("20:00"), TWO_HOURS, 6)));
        assertThat(ok(results)).isEqualTo(1);
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED");
    }

    /** C-3: 60 conferme casuali parallele (arrivi 18:00–21:00, 60–180 min, 1–6 persone) su capienza 10. */
    @RepeatedTest(5)
    void sessantaConfermeCasualiMaiOltreLaCapienza(RepetitionInfo repetition) {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        Random random = new Random(42L + repetition.getCurrentRepetition());
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Instant start = at("18:00").plus(Duration.ofMinutes(15L * random.nextInt(13)));
            Duration length = Duration.ofMinutes(15L * (random.nextInt(9) + 4));
            int guests = random.nextInt(6) + 1;
            tasks.add(() -> fixtures.confirm(zone, window, start, length, guests));
        }
        List<Result<UUID>> results = Concurrency.run(tasks);

        assertThat(ok(results)).isPositive();
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED");
        Integer peak = jdbc.sql("""
                SELECT max(p) FROM (
                  SELECT q, sum(r.guest_count) p
                    FROM generate_series(?::timestamptz, ?::timestamptz, interval '15 minutes') q
                    JOIN reservations r ON r.status = 'CONFIRMED' AND r.starts_at <= q AND r.occupied_until > q
                   GROUP BY q) x
                """).params(at("18:00").atOffset(java.time.ZoneOffset.UTC), at("23:45").atOffset(java.time.ZoneOffset.UTC))
                .query(Integer.class).single();
        assertThat(peak).as("presenza massima ricalcolata").isLessThanOrEqualTo(10);
    }

    /** C-5: spostamento di una confermata e nuova conferma che vogliono gli stessi posti → una sola. */
    @RepeatedTest(20)
    void spostamentoControConfermaSugliStessiPosti() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID booking = fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6);
        List<Result<Object>> results = Concurrency.run(List.of(
                () -> {
                    fixtures.change(booking, zone, at("21:00"), TWO_HOURS, 6);
                    return null;
                },
                () -> fixtures.confirm(zone, window, at("21:00"), TWO_HOURS, 6)));
        assertThat(ok(results)).isEqualTo(1);
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED");
    }

    /** C-6: uscita anticipata che libera posti e conferma che li chiede, insieme. */
    @RepeatedTest(20)
    void uscitaAnticipataControConferma() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        UUID booking = fixtures.confirm(zone, window, at("19:00"), Duration.ofHours(4), 10);
        List<Result<Object>> results = Concurrency.run(List.of(
                () -> {
                    fixtures.completeEarly(booking, at("21:00"));
                    return null;
                },
                () -> fixtures.confirm(zone, window, at("21:00"), TWO_HOURS, 10)));
        assertThat(results.get(0).ok()).as("l'uscita anticipata riesce sempre").isTrue();
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED");
    }

    /** C-7: riduzione della capienza e conferma in parallelo: mai entrambe se insieme superano la nuova capienza. */
    @RepeatedTest(20)
    void riduzioneCapienzaControConferma() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 6);
        List<Result<Object>> results = Concurrency.run(List.of(
                () -> {
                    fixtures.changeZoneCapacity(zone, 7);
                    return null;
                },
                () -> fixtures.confirm(zone, window, at("19:00"), TWO_HOURS, 4)));
        assertThat(ok(results)).isEqualTo(1);
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED", "CAPACITY_BELOW_CONFIRMED");
        Long staleSlots = jdbc.sql("""
                SELECT count(*) FROM capacity_slots s JOIN capacity_zones z ON z.id = s.zone_id
                 WHERE s.zone_id = ? AND s.capacity_covers <> z.physical_capacity
                """).param(zone).query(Long.class).single();
        assertThat(staleSlots).as("nessun quanto con la capienza vecchia").isZero();
    }

    /** C-8: zone diverse non si ostacolano. */
    @RepeatedTest(5)
    void zoneDiverseIndipendenti() {
        UUID sala = fixtures.zone("Sala", 20);
        UUID dehors = fixtures.zone("Dehors", 20);
        UUID windowSala = fixtures.window(sala);
        UUID windowDehors = fixtures.window(dehors);
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> fixtures.confirm(sala, windowSala, at("19:00"), TWO_HOURS, 2));
            tasks.add(() -> fixtures.confirm(dehors, windowDehors, at("19:00"), TWO_HOURS, 2));
        }
        List<Result<UUID>> results = Concurrency.run(tasks);
        assertThat(ok(results)).isEqualTo(20);
    }

    /** Coerenza dei contatori dopo un misto di conferme, annullamenti e modifiche concorrenti. */
    @RepeatedTest(5)
    void mistoDiConfermeAnnullamentiModifiche(RepetitionInfo repetition) {
        UUID sala = fixtures.zone("Sala", 12);
        UUID dehors = fixtures.zone("Dehors", 8);
        UUID window = fixtures.window(sala);
        List<UUID> existing = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            existing.add(fixtures.confirm(sala, window, at("19:00").plus(Duration.ofMinutes(30L * i)), TWO_HOURS, 2));
        }
        Random random = new Random(7L + repetition.getCurrentRepetition());
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < existing.size(); i++) {
            UUID booking = existing.get(i);
            if (i % 2 == 0) {
                tasks.add(() -> {
                    fixtures.cancel(booking);
                    return null;
                });
            } else {
                UUID target = random.nextBoolean() ? sala : dehors;
                Instant start = at("18:00").plus(Duration.ofMinutes(15L * random.nextInt(12)));
                int guests = random.nextInt(5) + 1;
                tasks.add(() -> {
                    fixtures.change(booking, target, start, TWO_HOURS, guests);
                    return null;
                });
            }
        }
        for (int i = 0; i < 20; i++) {
            UUID target = random.nextBoolean() ? sala : dehors;
            Instant start = at("18:00").plus(Duration.ofMinutes(15L * random.nextInt(12)));
            int guests = random.nextInt(4) + 1;
            tasks.add(() -> fixtures.confirm(target, window, start, TWO_HOURS, guests));
        }
        List<Result<Object>> results = Concurrency.run(tasks);
        assertOnlyExpectedFailures(results, "CAPACITY_EXCEEDED");
        // La riconciliazione dopo il test verifica registro = prenotazioni per ogni quanto.
    }
}
