package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;
import tools.jackson.databind.JsonNode;

/**
 * Worker dell'outbox (FASE 7, req. 11) con PostgreSQL reale e gateway finti: stati distinti, ritentativi controllati,
 * esiti incerti mai ritentati da soli, lease scaduti, scadenza, messaggi superati, nuovo invio manuale con audit,
 * più worker in parallelo senza doppioni. Nessun messaggio reale.
 */
class NotificationDispatcherIT extends ReservationTestSupport {

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    EmailGateway emailGateway;

    @Autowired
    SmsGateway smsGateway;

    private ApiClient owner;
    private ApiClient staff;

    private FakeEmailGateway email() {
        return (FakeEmailGateway) emailGateway;
    }

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
        email().reset();
        ((FakeSmsGateway) smsGateway).reset();
    }

    @AfterEach
    void templatesOff() {
        jdbc.sql("UPDATE notification_templates SET active = false").update();
    }

    private void templates(boolean active) {
        jdbc.sql("UPDATE notification_templates SET active = ?").param(active).update();
    }

    /** Notifica email al cliente «richiesta ricevuta» della prenotazione. */
    private UUID received(UUID reservation) {
        return jdbc.sql("""
                SELECT id FROM notification_outbox WHERE aggregate_id = ? AND template_code = 'RESERVATION_RECEIVED' AND recipient_kind = 'CUSTOMER'
                """).param(reservation).query(UUID.class).single();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.sql("SELECT state, skip_reason, attempts, last_error_code, provider_idempotency_key, manual_resend_count FROM notification_outbox WHERE id = ?")
                .param(id).query().singleRow();
    }

    private void due(UUID id) {
        jdbc.sql("UPDATE notification_outbox SET next_attempt_at = now() WHERE id = ?").param(id).update();
    }

    @Test
    void modelloSpentoSaltatoModelloAttivoInvioSimulato() {
        UUID first = pending("19:00", 2);
        dispatcher.runOnce();
        assertThat(row(received(first))).containsEntry("state", "SKIPPED").containsEntry("skip_reason", "TEMPLATE_INACTIVE");

        templates(true);
        UUID second = pending("19:30", 3);
        NotificationDispatcher.Round round = dispatcher.runOnce();
        assertThat(round.outcomes()).containsEntry("SENT", 1);
        assertThat(row(received(second))).containsEntry("state", "SENT");
        Map<String, Object> attempt = jdbc.sql("SELECT provider, outcome, idempotency_key_used, provider_message_id FROM notification_attempts WHERE outbox_id = ?")
                .param(received(second)).query().singleRow();
        assertThat(attempt).containsEntry("provider", "fake").containsEntry("outcome", "ACCEPTED");
        assertThat(attempt.get("idempotency_key_used")).isEqualTo(row(received(second)).get("provider_idempotency_key"));
        FakeEmailGateway.FakeEmail sent = email().delivered().getFirst();
        assertThat(sent.to()).isEqualTo("cliente@example.invalid");
        assertThat(sent.body()).contains("3 persone").doesNotContain("{{").doesNotContain("Tavolo vicino alla finestra");

        JsonNode notifications = staff.get("/api/v1/admin/reservations/" + second).body().path("notifications");
        JsonNode customer = null;
        for (JsonNode n : notifications) {
            if (n.path("template").asString().equals("RESERVATION_RECEIVED") && n.path("recipient").asString().equals("CUSTOMER")) {
                customer = n;
            }
        }
        assertThat(customer).isNotNull();
        assertThat(customer.path("simulated").asBoolean()).as("gateway finto").isTrue();
        assertThat(customer.path("delivered").asBoolean()).as("mai «consegnata» senza un provider reale").isFalse();
        assertThat(dispatcher.runOnce().outcomes()).as("niente da rifare").isEmpty();
    }

    @Test
    void erroriTemporaneiRitentatiPoiDeadEPoiNuovoInvioManuale() {
        templates(true);
        UUID reservation = pending("19:00", 2);
        UUID id = received(reservation);
        String key = (String) row(id).get("provider_idempotency_key");
        email().script(SendResult.Outcome.TRANSIENT_ERROR, SendResult.Outcome.TRANSIENT_ERROR, SendResult.Outcome.TRANSIENT_ERROR,
                SendResult.Outcome.TRANSIENT_ERROR, SendResult.Outcome.TRANSIENT_ERROR);
        dispatcher.runOnce();
        assertThat(row(id)).containsEntry("state", "FAILED").containsEntry("attempts", 1);
        assertThat(dispatcher.runOnce().outcomes()).as("attende il ritentativo").doesNotContainKey("FAILED");
        for (int i = 2; i <= 5; i++) {
            due(id);
            dispatcher.runOnce();
        }
        assertThat(row(id)).containsEntry("state", "DEAD").containsEntry("last_error_code", "FAKE_TRANSIENT_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_attempts WHERE outbox_id = ?").param(id).query(Long.class).single()).isEqualTo(5);
        assertThat(email().delivered()).isEmpty();

        assertThat(browser().post("/api/v1/admin/notifications/" + id + "/resend", Map.of()).status()).isEqualTo(401);
        assertThat(staff.postWithCsrf("/api/v1/admin/notifications/" + id + "/resend", Map.of(), "inventato").status()).isEqualTo(403);
        assertThat(staff.post("/api/v1/admin/notifications/" + id + "/resend", Map.of()).status()).isEqualTo(202);
        assertThat(row(id)).containsEntry("state", "PENDING").containsEntry("manual_resend_count", 1);
        dispatcher.runOnce();
        assertThat(row(id)).containsEntry("state", "SENT").containsEntry("provider_idempotency_key", key);
        assertThat(jdbc.sql("SELECT max(attempt_no) FROM notification_attempts WHERE outbox_id = ?").param(id).query(Integer.class).single()).isEqualTo(6);
        assertThat(audit("NOTIFICATION_RESENT")).isEqualTo(1);
        assertThat(staff.post("/api/v1/admin/notifications/" + id + "/resend", Map.of()).code()).as("già inviata").isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void esitoIgnotoRifiutoLeaseScadutoEScadenza() {
        templates(true);
        UUID unknown = received(pending("18:00", 2));
        email().script(SendResult.Outcome.UNKNOWN);
        dispatcher.runOnce();
        assertThat(row(unknown)).containsEntry("state", "UNCERTAIN");
        due(unknown);
        dispatcher.runOnce();
        assertThat(row(unknown)).as("mai ritentato da solo").containsEntry("state", "UNCERTAIN").containsEntry("attempts", 1);

        UUID rejected = received(pending("18:30", 2));
        email().script(SendResult.Outcome.REJECTED_PERMANENT);
        dispatcher.runOnce();
        assertThat(row(rejected)).containsEntry("state", "DEAD");

        UUID crashed = received(pending("19:00", 2));
        jdbc.sql("UPDATE notification_outbox SET state = 'SENDING', lease_token = gen_random_uuid(), locked_until = now() - interval '1 minute' WHERE id = ?")
                .param(crashed).update();
        assertThat(dispatcher.runOnce().recovered()).isEqualTo(1);
        assertThat(row(crashed)).containsEntry("state", "UNCERTAIN").containsEntry("last_error_code", "LEASE_EXPIRED");

        UUID stale = received(pending("19:30", 2));
        jdbc.sql("UPDATE notification_outbox SET expires_at = now() - interval '1 minute' WHERE id = ?").param(stale).update();
        dispatcher.runOnce();
        assertThat(row(stale)).containsEntry("state", "SKIPPED").containsEntry("skip_reason", "EXPIRED");
        assertThat(jdbc.sql("SELECT expires_at IS NOT NULL FROM notification_outbox WHERE id = ?").param(unknown).query(Boolean.class).single())
                .as("scadenza = fine della prenotazione").isTrue();
    }

    @Test
    void messaggioSuperatoNonParte() {
        templates(true);
        UUID reservation = pending("19:00", 2);
        assertThat(confirm(staff, reservation).status()).isEqualTo(200);
        assertThat(changeStatus(staff, reservation, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        dispatcher.runOnce();
        String confirmed = jdbc.sql("""
                SELECT state || ':' || coalesce(skip_reason, '') FROM notification_outbox
                 WHERE aggregate_id = ? AND template_code = 'RESERVATION_CONFIRMED' AND channel = 'EMAIL'
                """).param(reservation).query(String.class).single();
        assertThat(confirmed).isEqualTo("SKIPPED:SUPERSEDED");
    }

    @Test
    void piuWorkerInParalleloNessunDoppione() {
        templates(true);
        for (int i = 0; i < 60; i++) {
            jdbc.sql("""
                    INSERT INTO notification_outbox (business_event_key, aggregate_type, aggregate_id, channel, recipient_kind,
                                                     recipient, template_code, payload)
                    VALUES (?, 'RESERVATION', gen_random_uuid(), 'EMAIL', 'CUSTOMER', 'prova@example.invalid', 'RESERVATION_RECEIVED',
                            '{"riferimento":"FF-PROVA","persone":2,"data":"1 gennaio","ora":"19:00","nome":"Prova"}'::jsonb)
                    """).param("parallelo:" + i).update();
        }
        List<Callable<Integer>> workers = new ArrayList<>(Collections.nCopies(4, () -> {
            int sent = 0;
            for (NotificationDispatcher.Round r = dispatcher.runOnce(); !r.outcomes().isEmpty(); r = dispatcher.runOnce()) {
                sent += r.outcomes().getOrDefault("SENT", 0);
            }
            return sent;
        }));
        List<Concurrency.Result<Integer>> results = Concurrency.run(workers);
        assertThat(results).allMatch(Concurrency.Result::ok);
        assertThat(results.stream().mapToInt(Concurrency.Result::value).sum()).isEqualTo(60);
        assertThat(email().delivered()).hasSize(60);
        assertThat(email().delivered().stream().map(FakeEmailGateway.FakeEmail::idempotencyKey).distinct()).hasSize(60);
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE business_event_key LIKE 'parallelo:%' AND state = 'SENT'")
                .query(Long.class).single()).isEqualTo(60);
    }

    @Test
    void segnapostiMaiGrezzi() {
        assertThat(NotificationDispatcher.render("Ciao {{nome}}, {{ignoto}} rif. {{ riferimento }}", Map.of("nome", "Ada", "riferimento", "FF-1")))
                .isEqualTo("Ciao Ada,  rif. FF-1");
    }
}
