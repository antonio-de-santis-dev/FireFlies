package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/** Richieste dal sito: creazione IN ATTESA, validazioni, regole di prenotabilità, anti-abuso, orari, scadenza. */
class WebReservationIT extends ReservationTestSupport {

    private ApiClient owner;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        configure(owner, 20);
    }

    @Test
    void richiestaValidaRestaInAttesaENonOccupaPosti() {
        ApiClient.Response r = web(browser(), webBody(DAY, "19:00", 4, "333 123 4567"));

        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        assertThat(r.body().path("status").asString()).isEqualTo("PENDING");
        assertThat(r.body().path("reference").asString()).matches("FF-[A-Z2-9]{6}");
        assertThat(r.body().path("message").asString()).contains("non è ancora una prenotazione");
        assertThat(r.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        UUID id = idOf(r.body().path("reference").asString());
        Map<String, Object> row = jdbc.sql("""
                SELECT status, source, customer_phone_e164, customer_email, guest_count, capacity_zone_id, privacy_notice_version
                  FROM reservations WHERE id = ?
                """).param(id).query().singleRow();
        assertThat(row).containsEntry("status", "PENDING").containsEntry("source", "WEB")
                .containsEntry("customer_phone_e164", "+393331234567").containsEntry("guest_count", 4)
                .containsEntry("capacity_zone_id", zone).containsEntry("privacy_notice_version", ReservationService.PRIVACY_VERSION);
        assertThat(occupied("19:00")).as("una richiesta non conta nel registro").isZero();
        assertThat(history(id)).isEqualTo(1);
        assertThat(audit("RESERVATION_CREATED")).isEqualTo(1);
        // avviso al locale senza email pubblica: saltato; conferma di ricezione al cliente: preparata, non inviata
        assertThat(outbox(id)).extracting(m -> m.get("template_code") + "/" + m.get("channel") + "/" + m.get("state") + "/" + m.get("skip_reason"))
                .containsExactlyInAnyOrder("RESERVATION_NEW_FOR_VENUE/EMAIL/SKIPPED/NO_RECIPIENT",
                        "RESERVATION_RECEIVED/EMAIL/PENDING/null");
        // nel payload delle notifiche mai le note libere del cliente
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox WHERE payload::text LIKE '%finestra%'").query(Long.class).single()).isZero();
    }

    @Test
    void validazioniDeiCampi() {
        ApiClient client = browser();
        Map<String, Object> body = webBody(DAY, "19:00", 2, "");
        assertThat(web(client, body).code()).isEqualTo("VALIDATION_FAILED");

        body = webBody(DAY, "19:00", 2, "12");
        assertThat(web(client, body).status()).as("telefono troppo corto").isEqualTo(422);

        body = webBody(DAY, "19:00", 2, nextPhone());
        body.put("email", "non-una-email");
        assertThat(web(client, body).code()).isEqualTo("VALIDATION_FAILED");

        body = webBody(DAY, "19:00", 2, nextPhone());
        body.remove("email");
        assertThat(web(client, body).status()).as("l'email è facoltativa").isEqualTo(201);

        body = webBody(DAY, "19:30", 2, nextPhone());
        body.put("name", "A");
        assertThat(web(client, body).code()).isEqualTo("VALIDATION_FAILED");

        body = webBody(DAY, "19:30", 2, nextPhone());
        body.put("privacyAccepted", false);
        assertThat(web(client, body).code()).isEqualTo("VALIDATION_FAILED");

        body = webBody(DAY, "19:30", 2, nextPhone());
        body.put("notes", "x".repeat(501));
        assertThat(web(client, body).code()).isEqualTo("VALIDATION_FAILED");

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void regoleDiPrenotabilita() {
        ApiClient client = browser();
        assertThat(web(client, webBody(DAY, "19:10", 2, nextPhone())).code()).as("non è un arrivo della fascia").isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(client, webBody(DAY, "12:00", 2, nextPhone())).code()).isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(client, webBody(DAY.plusDays(1), "19:00", 2, nextPhone())).code()).as("nessuna fascia quel giorno")
                .isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(client, webBody(DAY, "19:00", 21, nextPhone())).code()).as("oltre la capienza del locale (20)")
                .isEqualTo("PARTY_TOO_LARGE");
        assertThat(web(client, webBody(DAY.plusDays(70), "19:00", 2, nextPhone())).code()).isEqualTo("TOO_FAR");
        webLimiter.clear(); // i tentativi rifiutati contano solo nel limite largo dei tentativi (FIX-1): qui si provano le regole

        clock.advance(Duration.between(Instant.now(), BusinessTime.toInstant(DAY, LocalTime.of(18, 30))));
        assertThat(web(client, webBody(DAY, "19:00", 2, nextPhone())).code()).as("anticipo minimo di un'ora").isEqualTo("TOO_EARLY");
        clock.reset();
        webLimiter.clear();

        ApiClient.Response closed = owner.post("/api/v1/admin/booking-exceptions",
                Map.of("serviceDate", DAY.toString(), "kind", "CLOSED", "reason", "Chiusura per prova"));
        assertThat(closed.status()).as(closed.raw()).isEqualTo(201);
        assertThat(web(client, webBody(DAY, "19:00", 2, nextPhone())).code()).isEqualTo("DAY_CLOSED");
        assertThat(owner.delete("/api/v1/admin/booking-exceptions/" + closed.body().path("id").asString()).status()).isEqualTo(204);

        policy(owner, false, 60, 60);
        webLimiter.clear();
        assertThat(web(client, webBody(DAY, "19:00", 2, nextPhone())).code()).isEqualTo("WEB_BOOKING_DISABLED");
        assertThat(count()).isZero();
    }

    @Test
    void orarioCompletoNelRegistroBloccaLaRichiesta() {
        ApiClient staff = signedIn(Role.STAFF);
        UUID big = pending("19:00", 12);
        UUID other = pending("19:00", 8);
        assertThat(confirm(staff, big).status()).isEqualTo(200);
        assertThat(confirm(staff, other).status()).isEqualTo(200);

        ApiClient.Response full = web(browser(), webBody(DAY, "20:00", 2, nextPhone()));
        assertThat(full.status()).isEqualTo(422);
        assertThat(full.code()).isEqualTo("FULLY_BOOKED");
        assertThat(web(browser(), webBody(DAY, "21:00", 2, nextPhone())).status()).as("alle 21 i primi sono usciti").isEqualTo(201);

        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY + "&guests=2").body();
        JsonNode slots = options.path("groups").get(0).path("slots");
        assertThat(slots.get(0).path("time").asString()).startsWith("18:00");
        assertThat(slots.get(0).path("available").asBoolean()).as("18:00–20:00 attraversa le 19").isFalse();
        assertThat(slot(slots, "19:30").path("reason").asString()).isEqualTo("FULL");
        assertThat(slot(slots, "21:00").path("available").asBoolean()).isTrue();
        assertThat(options.toString()).as("al pubblico mai i numeri dei posti").doesNotContain("capacity").doesNotContain("occupied");
    }

    @Test
    void orariPrenotabiliDelGiorno() {
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY).body();
        assertThat(options.path("webBookingEnabled").asBoolean()).isTrue();
        assertThat(options.path("maxPartySize").asInt()).as("capienza del locale (20), mai oltre 30").isEqualTo(20);
        assertThat(options.path("groups").get(0).path("name").asString()).isEqualTo("Aperitivo");
        assertThat(options.path("groups").get(0).path("slots").size()).isEqualTo(7);
        assertThat(browser().get("/api/v1/public/booking-options?date=" + DAY.plusDays(1)).body().path("groups").size()).isZero();

        policy(owner, false, 60, 60);
        assertThat(browser().get("/api/v1/public/booking-options?date=" + DAY).body().path("webBookingEnabled").asBoolean()).isFalse();
    }

    @Test
    void idempotenzaEDuplicati() {
        ApiClient client = browser();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = webBody(DAY, "19:00", 2, "333 765 4321");
        ApiClient.Response first = client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", key));
        ApiClient.Response again = client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", key));
        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).as("stesso invio ripetuto: stessa risposta").isEqualTo(201);
        assertThat(again.body().path("reference").asString()).isEqualTo(first.body().path("reference").asString());
        assertThat(count()).isEqualTo(1);

        body.put("guests", 3);
        assertThat(client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", key)).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        ApiClient.Response duplicate = web(client, webBody(DAY, "19:00", 2, "+39 333 765 4321"));
        assertThat(duplicate.status()).as("stesso telefono e stesso orario").isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("DUPLICATE_REQUEST");

        assertThat(client.post("/api/v1/public/reservations", webBody(DAY, "20:00", 2, nextPhone()), Map.of()).code())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(client.post("/api/v1/public/reservations", webBody(DAY, "20:00", 2, nextPhone()),
                Map.of("Idempotency-Key", "non-un-uuid")).status()).isEqualTo(422);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void campoNascostoCompilatoEScrittureSenzaCsrf() {
        Map<String, Object> body = webBody(DAY, "19:00", 2, nextPhone());
        body.put("website", "https://spam.example.invalid");
        assertThat(web(browser(), body).status()).isEqualTo(422);

        ApiClient.Response forged = browser().postWithoutCsrf("/api/v1/public/reservations", webBody(DAY, "19:00", 2, nextPhone()));
        assertThat(forged.status()).as("anche la richiesta pubblica passa dal controllo CSRF").isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test
    void limitiPerIndirizzoEPerTelefono() {
        String phone = "333 111 2222";
        ApiClient client = browser();
        for (String time : List.of("18:00", "18:30", "19:00")) {
            assertThat(web(client, webBody(DAY, time, 2, phone)).status()).isEqualTo(201);
        }
        ApiClient.Response perPhone = web(client, webBody(DAY, "19:30", 2, phone));
        assertThat(perPhone.status()).as("al massimo 3 richieste al giorno dallo stesso telefono").isEqualTo(429);
        assertThat(perPhone.body().path("context").path("retryAfterSeconds").asInt()).isPositive();

        assertThat(web(client, webBody(DAY, "20:00", 2, nextPhone())).status()).as("un altro telefono dallo stesso indirizzo")
                .isEqualTo(201);
        assertThat(count()).isEqualTo(4); // limiti per indirizzo: WebRateLimitIT (FIX-1)
    }

    @Test
    void richiesteNonGestiteScadonoPrimaDellArrivo() {
        UUID id = pending("19:00", 2);
        assertThat(reservations.expirePending()).isZero();

        Instant start = BusinessTime.toInstant(DAY, LocalTime.of(19, 0));
        clock.advance(Duration.between(Instant.now(), start).minusMinutes(59));
        assertThat(reservations.expirePending()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT status_reason FROM reservations WHERE id = ?").param(id).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(outbox(id)).extracting(m -> m.get("template_code")).contains("RESERVATION_EXPIRED");
        assertThat(audit("RESERVATION_EXPIRED")).isEqualTo(1);
        assertThat(reservations.expirePending()).as("una sola volta").isZero();
    }

    private static JsonNode slot(JsonNode slots, String time) {
        for (JsonNode s : slots) {
            if (s.path("time").asString().startsWith(time)) {
                return s;
            }
        }
        throw new AssertionError("orario assente: " + time);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single();
    }
}
