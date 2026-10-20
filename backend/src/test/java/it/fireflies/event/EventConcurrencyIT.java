package it.fireflies.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import it.fireflies.common.ApiException;
import it.fireflies.reservation.ReservationService;
import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;

/**
 * Gare tra eventi e prenotazioni (BOOKING_RULES §7.3): pubblicazione esclusiva contro conferma ordinaria nello stesso
 * orario (vince una sola), conferme simultanee sulla quota di un evento, annullamento dell'evento contro una conferma.
 * Dopo ogni ripetizione la riconciliazione verifica registro, quota degli eventi e quanti esclusivi senza ordinarie.
 */
class EventConcurrencyIT extends ReservationTestSupport {

    @Autowired
    EventService events;

    private EventService.Actor owner;
    private ReservationService.Actor staff;

    @BeforeEach
    void setUp() {
        configure(signedIn(Role.OWNER), 20);
        owner = new EventService.Actor(account(Role.OWNER).account().id(), true);
        staff = new ReservationService.Actor(account(Role.STAFF).account().id(), false);
    }

    private EventService.AdminEvent draft(String title, int quota) {
        return events.create(owner, new EventService.EventCommand(null, title, "SERATA", DAY, LocalTime.of(19, 0), LocalTime.of(22, 0),
                null, true, "REQUEST", quota, null, null, null, null, List.of()));
    }

    private UUID eventRequest(UUID eventId, int guests) {
        webLimiter.clear();
        return reservations.createManual(staff, new ReservationService.ManualRequest("PHONE", null, null, null, guests,
                "Cliente di prova", nextPhone(), null, null, null, null, false, null, false, eventId)).summary().id();
    }

    private ReservationService.StatusChange confirmBody(UUID id) {
        return new ReservationService.StatusChange(version(id), "CONFIRMED", null, null, null, null, false, null);
    }

    @RepeatedTest(10)
    void pubblicazioneEsclusivaControConfermaOrdinaria() {
        UUID ordinary = pending("19:30", 4);
        EventService.AdminEvent event = draft("Gara " + UUID.randomUUID(), 10);
        int version = event.version();
        ReservationService.StatusChange confirm = confirmBody(ordinary);

        List<Result<Object>> results = Concurrency.run(List.of(
                () -> reservations.changeStatus(staff, ordinary, confirm),
                () -> events.publish(owner, event.id(), version, true)));

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(Result::ok).as("vince una sola").hasSize(1);
        Throwable loser = results.stream().filter(r -> !r.ok()).findFirst().orElseThrow().error();
        String eventStatus = events.detail(event.id()).status();
        if (status(ordinary).equals("CONFIRMED")) {
            assertThat(eventStatus).isEqualTo("DRAFT");
            assertThat(((ApiException) loser).code()).isEqualTo("EVENT_CONFLICTS");
        } else {
            assertThat(eventStatus).isEqualTo("PUBLISHED");
            assertThat(((ApiException) loser).code()).isEqualTo("EVENT_BLOCKS_SLOT");
        }
    }

    @RepeatedTest(5)
    void confermeSimultaneeSullaQuotaDellEvento() {
        EventService.AdminEvent event = events.publish(owner, draft("Quota " + UUID.randomUUID(), 10).id(), 0, false);
        List<UUID> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(eventRequest(event.id(), 3));
        }
        List<Callable<Object>> tasks = requests.stream()
                .<Callable<Object>>map(id -> () -> reservations.changeStatus(staff, id, confirmBody(id))).toList();

        List<Result<Object>> results = Concurrency.run(tasks);

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(Result::ok).as("quota 10 / 3 persone").hasSize(3);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(((ApiException) r.error()).code()).isEqualTo("EVENT_QUOTA_EXCEEDED"));
        assertThat(events.detail(event.id()).confirmedCovers()).isEqualTo(9);
        assertThat(occupied("19:00")).isEqualTo(9);
    }

    @RepeatedTest(5)
    void annullamentoDellEventoControConferma() {
        EventService.AdminEvent event = events.publish(owner, draft("Annullo " + UUID.randomUUID(), 10).id(), 0, false);
        UUID first = eventRequest(event.id(), 2);
        UUID second = eventRequest(event.id(), 3);
        reservations.changeStatus(staff, first, confirmBody(first));
        int version = events.detail(event.id()).version();
        ReservationService.StatusChange confirmSecond = confirmBody(second);

        List<Result<Object>> results = Concurrency.run(List.of(
                () -> events.cancel(owner, event.id(), version, true),
                () -> reservations.changeStatus(staff, second, confirmSecond)));

        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results.getFirst().ok()).as(String.valueOf(results.getFirst().error())).isTrue();
        assertThat(events.detail(event.id()).status()).isEqualTo("CANCELLED");
        assertThat(status(first)).isEqualTo("CANCELLED");
        assertThat(status(second)).as("confermata e poi annullata, oppure annullata prima").isEqualTo("CANCELLED");
        assertThat(events.detail(event.id()).confirmedCovers()).isZero();
        assertThat(occupied("19:00")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM capacity_slots WHERE exclusive_event_id IS NOT NULL").query(Long.class).single()).isZero();
    }
}
