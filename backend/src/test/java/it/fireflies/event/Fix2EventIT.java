package it.fireflies.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * FIX 2 — eventi a qualunque minuto (nel registro occupano ogni quanto da 15' toccato anche in parte), durata automatica
 * invariata, nuovo evento sempre bozza, pubblicazione dall'elenco con l'interruttore e i controlli atomici di sempre.
 */
class Fix2EventIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
    }

    private static Map<String, Object> event(String title, java.time.LocalDate day, String start, String mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("kind", "SERATA");
        body.put("date", day.toString());
        body.put("startTime", start);
        body.put("exclusive", true);
        body.put("bookingMode", mode);
        body.put("artist", "DJ di prova");
        return body;
    }

    private JsonNode create(Map<String, Object> body) {
        ApiClient.Response r = staff.post("/api/v1/admin/events", body);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body();
    }

    private int version(JsonNode e) {
        return jdbc.sql("SELECT version FROM events WHERE id = ?").param(UUID.fromString(e.path("id").asString())).query(Integer.class).single();
    }

    private ApiClient.Response visibility(ApiClient client, JsonNode e, boolean visible) {
        return client.put("/api/v1/admin/events/" + e.path("id").asString() + "/visibility",
                Map.of("version", version(e), "visible", visible));
    }

    private String eventStatus(JsonNode e) {
        return jdbc.sql("SELECT status FROM events WHERE id = ?").param(UUID.fromString(e.path("id").asString())).query(String.class).single();
    }

    /** Il quanto che inizia all'ora indicata del giorno di prova è riservato a un evento esclusivo? */
    private boolean exclusiveAt(String time) {
        return jdbc.sql("SELECT count(*) FROM capacity_slots WHERE zone_id = ? AND slot_start = ? AND exclusive_event_id IS NOT NULL")
                .params(zone, BusinessTime.toInstant(DAY, LocalTime.parse(time)).atOffset(ZoneOffset.UTC)).query(Long.class).single() > 0;
    }

    @Test
    void orariAQualunqueMinutoConDurataAutomaticaEFineDopoMezzanotte() {
        JsonNode a = create(event("Alle 18:07", DAY.plusDays(1), "18:07", "NONE"));
        JsonNode b = create(event("Alle 19:23", DAY.plusDays(2), "19:23", "NONE"));
        JsonNode c = create(event("Alle 21:58", DAY.plusDays(3), "21:58", "NONE"));
        assertThat(a.path("startTime").asString()).startsWith("18:07");
        assertThat(a.path("endTime").asString()).as("4 ore standard").startsWith("22:07");
        assertThat(b.path("endTime").asString()).startsWith("23:23");
        assertThat(c.path("endTime").asString()).as("fine dopo mezzanotte").startsWith("01:58");
        assertThat(jdbc.sql("SELECT (ends_at - starts_at) = interval '4 hours' FROM events WHERE id = ?")
                .param(UUID.fromString(c.path("id").asString())).query(Boolean.class).single()).isTrue();
        for (JsonNode e : new JsonNode[] {a, b, c}) {
            assertThat(e.path("status").asString()).as("nuovo evento = bozza").isEqualTo("DRAFT");
        }
        ApiClient.Response seconds = staff.post("/api/v1/admin/events", event("Con secondi", DAY, "19:23:30", "NONE"));
        assertThat(seconds.code()).isEqualTo("INVALID_TIME");

        // modifica dell'orario di un evento esistente: la durata già salvata resta la stessa
        Map<String, Object> moved = event("Alle 19:23", DAY.plusDays(2), "20:41", "NONE");
        moved.put("version", version(b));
        JsonNode updated = staff.put("/api/v1/admin/events/" + b.path("id").asString(), moved).body();
        assertThat(updated.path("startTime").asString()).startsWith("20:41");
        assertThat(updated.path("endTime").asString()).startsWith("00:41");
        assertThat(updated.path("status").asString()).as("la modifica non cambia la visibilità").isEqualTo("DRAFT");
    }

    @Test
    void quantiToccatiInParteSonoRiservatiEIConfiniNonPassano() {
        JsonNode e = create(event("Serata 19:07", DAY, "19:07", "REQUEST")); // 19:07–23:07 → quanti 19:00–23:15
        assertThat(visibility(staff, e, true).status()).isEqualTo(200);
        assertThat(exclusiveAt("18:45")).isFalse();
        assertThat(exclusiveAt("19:00")).as("toccato dalle 19:07").isTrue();
        assertThat(exclusiveAt("23:00")).as("toccato fino alle 23:07").isTrue();
        assertThat(exclusiveAt("23:15")).isFalse();

        // ordinaria 18:00–20:00 tocca il quanto delle 19:00: bloccata
        assertThat(web(browser(), webBody(DAY, "18:00", 2, nextPhone())).code()).isEqualTo("EVENT_BLOCKS_SLOT");
        // inserimento dal telefono 18:00–19:00: finisce prima del quanto dell'evento, passa
        Map<String, Object> phone = new HashMap<>(Map.of("source", "PHONE", "date", DAY.toString(), "time", "18:00", "durationMinutes", 60,
                "guests", 2, "name", "Cliente al telefono", "phone", "0836 000 111", "confirmNow", true));
        ApiClient.Response before = staff.post("/api/v1/admin/reservations", phone);
        assertThat(before.status()).as(before.raw()).isEqualTo(201);
        phone.put("durationMinutes", 75);
        phone.put("phone", "0836 000 112");
        assertThat(staff.post("/api/v1/admin/reservations", phone).code()).as("18:00–19:15 tocca le 19:07").isEqualTo("EVENT_BLOCKS_SLOT");

        // richiesta per l'evento: occupa i quanti 19:00–23:00 compresi quelli toccati in parte
        Map<String, Object> request = webBody(DAY, "00:00", 6, nextPhone());
        request.put("eventId", e.path("id").asString());
        ApiClient.Response r = web(browser(), request);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        UUID id = idOf(r.body().path("reference").asString());
        assertThat(confirm(staff, id).status()).isEqualTo(200);
        assertThat(occupied("19:00")).isEqualTo(6);
        assertThat(occupied("23:00")).isEqualTo(6);
        assertThat(occupied("23:15")).isZero();
        assertThat(jdbc.sql("SELECT to_char(starts_at AT TIME ZONE 'Europe/Rome', 'HH24:MI') FROM reservations WHERE id = ?").param(id)
                .query(String.class).single()).as("orario dell'evento salvato com'è").isEqualTo("19:07");

        // hiding con prenotazioni attive: rifiutato, resta pubblicato
        ApiClient.Response hide = visibility(staff, e, false);
        assertThat(hide.code()).isEqualTo("EVENT_HAS_ACTIVE_RESERVATIONS");
        assertThat(eventStatus(e)).isEqualTo("PUBLISHED");
    }

    @Test
    void sovrapposizioniIncompatibiliRifiutateAncheAiConfini() {
        JsonNode first = create(event("Prima", DAY, "19:07", "NONE")); // fino alle 23:07
        assertThat(visibility(staff, first, true).status()).isEqualTo(200);
        JsonNode touching = create(event("Al confine", DAY, "23:10", "NONE")); // tocca il quanto 23:00–23:15
        ApiClient.Response clash = visibility(staff, touching, true);
        assertThat(clash.code()).isEqualTo("EVENT_CONFLICTS");
        assertThat(clash.body().path("context").path("otherEvent").asBoolean()).isTrue();
        assertThat(eventStatus(touching)).as("nessuno stato a metà").isEqualTo("DRAFT");
        JsonNode after = create(event("Dopo", DAY, "23:15", "NONE"));
        assertThat(visibility(staff, after, true).status()).as("quanto successivo: nessun conflitto").isEqualTo(200);
    }

    @Test
    void interruttoreDiPubblicazioneEPermessi() {
        JsonNode e = create(event("Interruttore", DAY.plusDays(4), "21:30", "NONE"));
        assertThat(e.path("status").asString()).isEqualTo("DRAFT");
        assertThat(browser().put("/api/v1/admin/events/" + e.path("id").asString() + "/visibility",
                Map.of("version", version(e), "visible", true)).status()).as("anonimo").isIn(401, 403);

        ApiClient.Response on = visibility(staff, e, true);
        assertThat(on.status()).as(on.raw()).isEqualTo(200);
        assertThat(on.body().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(staff.get("/api/v1/admin/events/" + e.path("id").asString()).body().path("status").asString())
                .as("dopo una nuova lettura").isEqualTo("PUBLISHED");
        assertThat(browser().get("/api/v1/public/events").body().toString()).contains("Interruttore");
        assertThat(visibility(owner, e, true).status()).as("già pubblicato: stessa risposta").isEqualTo(200);

        ApiClient.Response stale = staff.put("/api/v1/admin/events/" + e.path("id").asString() + "/visibility",
                Map.of("version", version(e) - 1, "visible", false));
        assertThat(stale.code()).as("versione superata").isEqualTo("VERSION_CONFLICT");
        assertThat(eventStatus(e)).isEqualTo("PUBLISHED");

        ApiClient.Response off = visibility(owner, e, false);
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(browser().get("/api/v1/public/events").body().toString()).doesNotContain("Interruttore");
        assertThat(audit("EVENT_PUBLISHED")).isEqualTo(1);
        assertThat(audit("EVENT_UNPUBLISHED")).isEqualTo(1);
    }
}
