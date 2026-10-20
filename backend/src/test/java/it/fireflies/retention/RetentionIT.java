package it.fireflies.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import it.fireflies.common.BusinessTime;
import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;
import tools.jackson.databind.JsonNode;

/**
 * Pulizia notturna (FASE 7, req. 4, 5, 12) con PostgreSQL reale: prenotazioni dopo un mese diventano statistiche
 * anonime, mai quelle future o con notifiche da inviare; registro coerente; eventi dopo due mesi; idempotenza e
 * concorrenza. L'orologio dei test si sposta in avanti per invecchiare i dati creati dalle API.
 */
class RetentionIT extends ReservationTestSupport {

    @Autowired
    RetentionService retention;

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
    }

    /** L'outbox in attesa la smaltisce il worker delle notifiche: qui si simula che sia già stata elaborata. */
    private void notificationsProcessed() {
        jdbc.sql("UPDATE notification_outbox SET state = 'SKIPPED', skip_reason = 'TEMPLATE_INACTIVE' WHERE state = 'PENDING'").update();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** Sposta l'orologio a «oggi» = DAY + 1 mese + {@code extraDays}. */
    private LocalDate travel(int extraDays) {
        LocalDate target = DAY.plusMonths(1).plusDays(extraDays);
        LocalDate today = BusinessTime.serviceDate(Instant.now());
        clock.advance(Duration.ofDays(ChronoUnit.DAYS.between(today, target)));
        return target;
    }

    @Test
    void prenotazioniPassateDiventanoStatisticheAnonime() {
        UUID confirmed = pending("19:00", 4);
        assertThat(confirm(staff, confirmed).status()).isEqualTo(200);
        UUID rejected = pending("18:00", 2);
        assertThat(changeStatus(staff, rejected, "REJECTED", Map.of("reason", "NO_CAPACITY")).status()).isEqualTo(200);
        UUID cancelled = pending("20:00", 3);
        assertThat(changeStatus(staff, cancelled, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        notificationsProcessed();
        UUID waitingNotification = pending("18:30", 2); // la sua notifica resta da inviare
        assertThat(count("SELECT count(*) FROM capacity_slots WHERE occupied_covers > 0")).isPositive();

        // dopo 1 mese meno un giorno non si tocca nulla
        LocalDate early = travel(-1);
        assertThat(retention.run(early).get("reservations")).isZero();
        assertThat(count("SELECT count(*) FROM reservations")).isEqualTo(4);

        clock.reset();
        LocalDate today = travel(1);
        Map<String, Integer> deleted = retention.run(today);
        assertThat(deleted.get("reservations")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM reservations WHERE id = ?", waitingNotification)).as("notifica da inviare").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reservations WHERE id IN (?, ?, ?)", confirmed, rejected, cancelled)).isZero();
        assertThat(count("SELECT count(*) FROM reservation_status_history WHERE reservation_id = ?", confirmed)).isZero();
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?", confirmed))
                .as("le notifiche con telefono ed email vanno con la prenotazione").isZero();
        assertThat(count("SELECT count(*) FROM capacity_slots")).as("registro del passato liberato").isZero();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE object_id = ?", confirmed.toString()))
                .as("l'audit (senza dati personali) resta").isPositive();

        Map<String, Object> stats = jdbc.sql("SELECT * FROM reservation_stats WHERE service_date = ?").param(DAY).query().singleRow();
        assertThat(stats.get("band")).isEqualTo("APERITIVO");
        assertThat(stats.get("requests")).isEqualTo(3);
        assertThat(stats.get("confirmed")).isEqualTo(1);
        assertThat(stats.get("confirmed_covers")).isEqualTo(4);
        assertThat(stats.get("rejected")).isEqualTo(1);
        assertThat(stats.get("cancelled")).isEqualTo(1);
        assertThat(stats.get("from_web")).isEqualTo(3);
        assertThat(stats.get("capacity_covers")).isEqualTo(20);
        assertThat(stats.toString()).as("nessun dato personale").doesNotContain("Cliente").doesNotContain("+39");

        // il riepilogo del giorno resta leggibile dalle statistiche (+ la richiesta rimasta)
        JsonNode rows = staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY).body().path("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("requests").asInt()).isEqualTo(4);
        assertThat(rows.get(0).path("confirmed").asInt()).isEqualTo(1);
        assertThat(rows.get(0).path("guests").asInt()).isEqualTo(4);
        assertThat(rows.get(0).path("fromStatistics").asBoolean()).isTrue();

        // seconda passata: niente di nuovo, statistiche non raddoppiate
        assertThat(retention.run(today).get("reservations")).isZero();
        assertThat(jdbc.sql("SELECT requests FROM reservation_stats WHERE service_date = ?").param(DAY).query(Integer.class).single())
                .isEqualTo(3);
    }

    @Test
    void prenotazioniFutureEInCorsoMaiToccate() {
        UUID future = pending("19:00", 2);
        assertThat(confirm(staff, future).status()).isEqualTo(200);
        notificationsProcessed();
        Map<String, Integer> deleted = retention.run(BusinessTime.serviceDate(Instant.now()));
        assertThat(deleted.get("reservations")).isZero();
        assertThat(deleted.get("capacitySlots")).isZero();
        assertThat(status(future)).isEqualTo("CONFIRMED");
        assertThat(occupied("19:00")).isEqualTo(2);
    }

    @Test
    void eventiDopoDueMesiSenzaPrenotazioni() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Serata vecchia");
        body.put("kind", "SERATA");
        body.put("date", DAY.toString());
        body.put("startTime", "23:00");
        body.put("endTime", "23:45");
        body.put("bookingMode", "REQUEST");
        body.put("capacityCovers", 10);
        body.put("visible", true);
        ApiClient.Response old = staff.post("/api/v1/admin/events", body);
        assertThat(old.status()).as(old.raw()).isEqualTo(201);
        UUID eventId = UUID.fromString(old.body().path("id").asString());
        Map<String, Object> request = webBody(DAY, "00:00", 2, nextPhone());
        request.put("eventId", eventId.toString());
        UUID booking = idOf(web(browser(), request).body().path("reference").asString());
        assertThat(confirm(staff, booking).status()).isEqualTo(200);
        body.put("title", "Format senza data");
        body.put("kind", "FORMAT");
        body.remove("date");
        body.remove("startTime");
        body.remove("endTime");
        body.put("bookingMode", "NONE");
        body.remove("capacityCovers");
        assertThat(staff.post("/api/v1/admin/events", body).status()).isEqualTo(201);
        notificationsProcessed();

        // dopo un mese e qualche giorno: la prenotazione è andata, l'evento resta (meno di due mesi)
        LocalDate oneMonth = travel(2);
        Map<String, Integer> first = retention.run(oneMonth);
        assertThat(first.get("reservations")).isEqualTo(1);
        assertThat(first.get("events")).isZero();
        assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT confirmed_covers FROM events WHERE id = ?").param(eventId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT for_events FROM reservation_stats WHERE service_date = ?").param(DAY).query(Integer.class).single())
                .isEqualTo(1);

        clock.reset();
        LocalDate twoMonths = DAY.plusMonths(2).plusDays(1);
        clock.advance(Duration.ofDays(ChronoUnit.DAYS.between(BusinessTime.serviceDate(Instant.now()), twoMonths)));
        Map<String, Integer> second = retention.run(twoMonths);
        assertThat(second.get("events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM events WHERE title = 'Format senza data'")).as("un format senza date resta").isEqualTo(1);
    }

    @Test
    void unaSolaEsecuzioneAlGiornoAncheInParallelo() {
        UUID old = pending("19:00", 2);
        notificationsProcessed();
        travel(1);
        List<java.util.concurrent.Callable<Boolean>> tasks = java.util.Collections.nCopies(8, () -> retention.runIfDue().executed());
        List<Concurrency.Result<Boolean>> results = Concurrency.run(tasks);
        assertThat(results).allMatch(Concurrency.Result::ok);
        assertThat(results.stream().filter(r -> r.value()).count()).as("una sola esecuzione per giorno").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reservations WHERE id = ?", old)).isZero();
        List<Map<String, Object>> runs = retention.lastRuns(5);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("status")).isEqualTo("DONE");
        assertThat(String.valueOf(runs.get(0).get("details"))).contains("\"reservations\": 1");
        assertThat(retention.runIfDue().executed()).as("idempotente: il giorno è fatto").isFalse();

        // un tentativo fallito si riprende all'orario successivo
        jdbc.sql("UPDATE maintenance_runs SET status = 'FAILED'").update();
        assertThat(retention.runIfDue().executed()).isTrue();
        assertThat(jdbc.sql("SELECT attempts FROM maintenance_runs").query(Integer.class).single()).isEqualTo(2);
    }
}
