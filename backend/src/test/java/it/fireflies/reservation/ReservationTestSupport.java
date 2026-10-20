package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.SecurityIntegrationTest;
import tools.jackson.databind.JsonNode;

/**
 * Base dei test delle prenotazioni: configurazione tramite le API del titolare (zona, fascia serale, regole),
 * richieste dal sito con Idempotency-Key e cambi di stato via HTTP reale. Numeri di telefono solo di prova.
 */
public abstract class ReservationTestSupport extends SecurityIntegrationTest {

    /** Giorno di servizio delle prove: fra una settimana, sempre entro i 60 giorni prenotabili. */
    protected static final LocalDate DAY = BusinessTime.serviceDate(Instant.now()).plusDays(7);

    @Autowired
    protected ReservationService reservations;

    protected UUID zone;
    protected UUID window;
    private int phoneCounter;

    protected ApiClient signedIn(Role role) {
        TestUser user = user(role.name().toLowerCase() + "." + UUID.randomUUID() + "@example.invalid", role);
        ApiClient client = browser();
        assertThat(client.login(user.email(), user.password()).status()).isEqualTo(200);
        return client;
    }

    protected TestUser account(Role role) {
        return user(role.name().toLowerCase() + "." + UUID.randomUUID() + "@example.invalid", role);
    }

    /** Zona «Locale» attiva con la capienza data, fascia 18:00–21:00 ogni 30' per 2 ore, richieste dal sito aperte. */
    protected void configure(ApiClient owner, int capacity) {
        JsonNode config = owner.get("/api/v1/admin/booking-config").body();
        JsonNode locale = config.path("zones").get(0);
        zone = UUID.fromString(locale.path("id").asString());
        assertThat(owner.put("/api/v1/admin/capacity-zones/" + zone, Map.of("version", locale.path("version").asInt(),
                "name", "Locale", "physicalCapacity", capacity, "active", true, "sortOrder", 1)).status()).isEqualTo(200);
        Map<String, Object> w = new HashMap<>();
        w.put("zoneId", zone);
        w.put("isoDay", DAY.getDayOfWeek().getValue());
        w.put("name", "Aperitivo");
        w.put("firstStart", "18:00");
        w.put("lastStart", "21:00");
        w.put("slotMinutes", 30);
        w.put("defaultDurationMinutes", 120);
        ApiClient.Response created = owner.post("/api/v1/admin/service-windows", w);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        window = UUID.fromString(created.body().path("id").asString());
        policy(owner, true, 60, 60, 12);
    }

    protected void policy(ApiClient owner, boolean web, int lead, int advance, int party) {
        ApiClient.Response r = owner.put("/api/v1/admin/booking-policy", Map.of("webBookingEnabled", web, "minLeadMinutes", lead,
                "maxAdvanceDays", advance, "maxPartySizeWeb", party, "pendingExpiryMinutesBeforeStart", 60));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
    }

    protected String nextPhone() {
        return String.format("+39 300 000 %04d", ++phoneCounter);
    }

    protected static Map<String, Object> webBody(LocalDate date, String time, int guests, String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date.toString());
        body.put("time", time);
        body.put("guests", guests);
        body.put("name", "Cliente di prova");
        body.put("phone", phone);
        body.put("email", "cliente@example.invalid");
        body.put("notes", "Tavolo vicino alla finestra");
        body.put("privacyAccepted", true);
        return body;
    }

    protected static ApiClient.Response web(ApiClient client, Map<String, Object> body) {
        return client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    /** Richiesta dal sito andata a buon fine: id della prenotazione IN ATTESA. */
    protected UUID pending(String time, int guests) {
        ApiClient.Response r = web(browser(), webBody(DAY, time, guests, nextPhone()));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return idOf(r.body().path("reference").asString());
    }

    protected UUID idOf(String reference) {
        return jdbc.sql("SELECT id FROM reservations WHERE public_ref = ?").param(reference).query(UUID.class).single();
    }

    protected int version(UUID id) {
        return jdbc.sql("SELECT version FROM reservations WHERE id = ?").param(id).query(Integer.class).single();
    }

    protected String status(UUID id) {
        return jdbc.sql("SELECT status FROM reservations WHERE id = ?").param(id).query(String.class).single();
    }

    protected ApiClient.Response changeStatus(ApiClient client, UUID id, String status, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("version", version(id));
        body.put("status", status);
        return client.patch("/api/v1/admin/reservations/" + id + "/status", body);
    }

    protected ApiClient.Response confirm(ApiClient client, UUID id) {
        return changeStatus(client, id, "CONFIRMED", Map.of());
    }

    /** Persone confermate nel registro al quanto che inizia all'ora locale indicata del giorno di prova. */
    protected int occupied(String localTime) {
        Instant slot = BusinessTime.toInstant(DAY, LocalTime.parse(localTime));
        return jdbc.sql("SELECT coalesce(max(occupied_covers), 0) FROM capacity_slots WHERE zone_id = ? AND slot_start = ?")
                .params(zone, slot.atOffset(java.time.ZoneOffset.UTC)).query(Integer.class).single();
    }

    protected List<Map<String, Object>> outbox(UUID id) {
        return jdbc.sql("""
                SELECT template_code, channel, recipient_kind, state, skip_reason FROM notification_outbox
                 WHERE aggregate_id = ? ORDER BY created_at, template_code, channel
                """).param(id).query().listOfRows();
    }

    protected long history(UUID id) {
        return jdbc.sql("SELECT count(*) FROM reservation_status_history WHERE reservation_id = ?").param(id).query(Long.class).single();
    }

    protected UUID table(ApiClient owner, String code, int min, int max) {
        String areaId = owner.get("/api/v1/admin/booking-config").body().path("areas").valueStream()
                .filter(a -> a.path("name").asString().equals("Sala")).map(a -> a.path("id").asString()).findFirst()
                .orElseGet(() -> owner.post("/api/v1/admin/areas", Map.of("zoneId", zone, "name", "Sala")).body().path("id").asString());
        ApiClient.Response r = owner.post("/api/v1/admin/tables", Map.of("areaId", areaId, "code", code, "minSeats", min, "maxSeats", max));
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return UUID.fromString(r.body().path("id").asString());
    }
}
