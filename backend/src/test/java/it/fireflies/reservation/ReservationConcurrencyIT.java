package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import it.fireflies.common.ApiException;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;

/**
 * Gestori che agiscono nello stesso istante: il registro non va mai oltre la capienza, una richiesta si conferma una
 * volta sola, un tavolo non finisce a due prenotazioni sovrapposte, un doppio invio dal sito crea una sola riga.
 * Dopo ogni ripetizione la riconciliazione (registro = somma delle prenotazioni) deve risultare pulita.
 */
class ReservationConcurrencyIT extends ReservationTestSupport {

    private ApiClient owner;
    private ReservationService.Actor first;
    private ReservationService.Actor second;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        configure(owner, 20);
        first = new ReservationService.Actor(account(Role.STAFF).account().id(), false);
        second = new ReservationService.Actor(account(Role.STAFF).account().id(), false);
    }

    @RepeatedTest(5)
    void confermeSimultaneeNonSuperanoLaCapienza() {
        List<UUID> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(pending("19:00", 4));
            webLimiter.clear();
        }
        List<Callable<ReservationViews.Detail>> tasks = requests.stream()
                .<Callable<ReservationViews.Detail>>map(id -> () -> reservations.changeStatus(i(id) ? first : second, id,
                        new ReservationService.StatusChange(version(id), "CONFIRMED", null, null, null, null, false, null)))
                .toList();

        List<Result<ReservationViews.Detail>> results = Concurrency.run(tasks);

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(Result::ok).as("20 posti / 4 persone").hasSize(5);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(((ApiException) r.error()).code()).isEqualTo("CAPACITY_EXCEEDED"));
        assertThat(occupied("19:00")).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations WHERE status = 'PENDING'").query(Long.class).single()).isEqualTo(3);
    }

    @RepeatedTest(5)
    void dueGestoriConfermanoLaStessaRichiesta() {
        UUID id = pending("19:00", 6);
        int version = version(id);
        ReservationService.StatusChange confirm = new ReservationService.StatusChange(version, "CONFIRMED", null, null, null, null,
                false, null);
        ReservationService.StatusChange reject = new ReservationService.StatusChange(version, "REJECTED", "NO_CAPACITY", null, null,
                null, false, null);

        List<Result<ReservationViews.Detail>> results = Concurrency.run(List.of(
                () -> reservations.changeStatus(first, id, confirm),
                () -> reservations.changeStatus(second, id, confirm),
                () -> reservations.changeStatus(second, id, reject)));

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(((ApiException) r.error()).code()).isIn("VERSION_CONFLICT", "INVALID_TRANSITION"));
        assertThat(history(id)).as("un solo cambio di stato registrato").isEqualTo(2);
        String status = status(id);
        assertThat(status).isIn("CONFIRMED", "REJECTED");
        assertThat(occupied("19:00")).isEqualTo(status.equals("CONFIRMED") ? 6 : 0);
        assertThat(outbox(id)).filteredOn(m -> m.get("channel").equals("EMAIL")
                && List.of("RESERVATION_CONFIRMED", "RESERVATION_REJECTED").contains(m.get("template_code"))).hasSize(1);
    }

    @RepeatedTest(5)
    void stessoTavoloADuePrenotazioniSovrapposte() {
        UUID table = table(owner, "T4", 2, 4);
        UUID a = pending("19:00", 4);
        UUID b = pending("20:00", 4);
        ReservationService.Actor ownerActor = new ReservationService.Actor(account(Role.OWNER).account().id(), true);
        reservations.changeStatus(ownerActor, a, new ReservationService.StatusChange(version(a), "CONFIRMED", null, null, null, null, false, null));
        reservations.changeStatus(ownerActor, b, new ReservationService.StatusChange(version(b), "CONFIRMED", null, null, null, null, false, null));

        List<Result<ReservationViews.Detail>> results = Concurrency.run(List.of(
                () -> reservations.changeTables(first, a, new ReservationService.TablesChange(version(a), List.of(table), false, null)),
                () -> reservations.changeTables(second, b, new ReservationService.TablesChange(version(b), List.of(table), false, null))));

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(Result::ok).hasSize(1);
        assertThat(results).filteredOn(r -> !r.ok()).singleElement()
                .satisfies(r -> assertThat(Concurrency.hasSqlState(r.error(), "23P01")).as(String.valueOf(r.error())).isTrue());
        assertThat(jdbc.sql("SELECT count(*) FROM reservation_table_assignments WHERE active").query(Long.class).single()).isEqualTo(1);
    }

    @RepeatedTest(5)
    void doppioInvioSimultaneoDalSito() {
        String key = UUID.randomUUID().toString();
        java.util.Map<String, Object> body = webBody(DAY, "19:00", 2, nextPhone());
        List<Result<ApiClient.Response>> results = Concurrency.run(List.of(
                () -> browser().post("/api/v1/public/reservations", body, java.util.Map.of("Idempotency-Key", key)),
                () -> browser().post("/api/v1/public/reservations", body, java.util.Map.of("Idempotency-Key", key)),
                () -> web(browser(), body)));

        assertThat(results).allMatch(Result::ok);
        assertThat(results).extracting(r -> r.value().status()).allMatch(s -> s == 201 || s == 409);
        assertThat(results).extracting(r -> r.value().status()).contains(201);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).as("una sola richiesta").isEqualTo(1);
    }

    @RepeatedTest(5)
    void confermaEScadenzaNelloStessoIstante() {
        UUID id = pending("19:00", 4);
        clock.advance(java.time.Duration.between(java.time.Instant.now(),
                it.fireflies.common.BusinessTime.toInstant(DAY, LocalTime.of(18, 30))));

        List<Result<Object>> results = Concurrency.run(List.of(
                () -> reservations.changeStatus(first, id, new ReservationService.StatusChange(version(id), "CONFIRMED", null, null,
                        null, null, false, null)),
                () -> reservations.expirePending()));

        assertThat(results).noneMatch(Result::deadlock);
        String status = status(id);
        assertThat(status).isIn("CONFIRMED", "REJECTED");
        assertThat(occupied("19:00")).isEqualTo(status.equals("CONFIRMED") ? 4 : 0);
        assertThat(history(id)).isEqualTo(2);
    }

    private boolean i(UUID id) {
        return id.hashCode() % 2 == 0;
    }
}
