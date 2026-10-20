package it.fireflies.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.TestImages;
import tools.jackson.databind.JsonNode;

/**
 * Eventi via HTTP reale: permessi, ciclo di vita, riserva esclusiva nel registro, conflitti con le prenotazioni
 * ordinarie, prenotazioni per evento con quota, eventi non esclusivi sullo stesso registro, annullamento, modifica, foto.
 * Locale di prova: 20 posti, fascia 18:00–21:00 (arrivi ogni 30', 2 ore) nel giorno {@code DAY}.
 */
class EventApiIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
    }

    private static Map<String, Object> event(String title, String start, String end, boolean exclusive, String mode, Integer quota) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("kind", "SERATA");
        body.put("date", DAY.toString());
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("exclusive", exclusive);
        body.put("bookingMode", mode);
        body.put("capacityCovers", quota);
        body.put("summary", "Serata di prova");
        body.put("body", "Primo paragrafo.\n\nSecondo paragrafo.");
        return body;
    }

    private JsonNode create(ApiClient client, Map<String, Object> body) {
        ApiClient.Response r = client.post("/api/v1/admin/events", body);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.body();
    }

    private ApiClient.Response publish(ApiClient client, JsonNode event, boolean acknowledge) {
        return client.post("/api/v1/admin/events/" + event.path("id").asString() + "/publish",
                Map.of("version", currentVersion(event), "acknowledgePending", acknowledge));
    }

    private int currentVersion(JsonNode event) {
        return jdbc.sql("SELECT version FROM events WHERE id = ?").param(UUID.fromString(event.path("id").asString()))
                .query(Integer.class).single();
    }

    private JsonNode published(ApiClient client, Map<String, Object> body) {
        JsonNode created = create(client, body);
        ApiClient.Response p = publish(client, created, false);
        assertThat(p.status()).as(p.raw()).isEqualTo(200);
        return p.body();
    }

    private long exclusiveSlots(JsonNode event) {
        return jdbc.sql("SELECT count(*) FROM capacity_slots WHERE exclusive_event_id = ?")
                .param(UUID.fromString(event.path("id").asString())).query(Long.class).single();
    }

    private UUID eventRequest(JsonNode event, int guests) {
        Map<String, Object> body = webBody(DAY, "00:00", guests, nextPhone());
        body.put("eventId", event.path("id").asString());
        ApiClient.Response r = web(browser(), body);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return idOf(r.body().path("reference").asString());
    }

    private int confirmedCovers(JsonNode event) {
        return jdbc.sql("SELECT confirmed_covers FROM events WHERE id = ?").param(UUID.fromString(event.path("id").asString()))
                .query(Integer.class).single();
    }

    @Test
    void permessiECsrf() {
        assertThat(browser().get("/api/v1/admin/events").status()).isEqualTo(401);
        assertThat(staff.get("/api/v1/admin/events").status()).isEqualTo(200);
        assertThat(create(staff, event("Serata dello staff", "19:00", "22:00", true, "NONE", null)).path("exclusive").asBoolean())
                .as("lo staff crea eventi esclusivi").isTrue();
        ApiClient.Response shared = staff.post("/api/v1/admin/events", event("Condivisa", "19:00", "22:00", false, "NONE", null));
        assertThat(shared.status()).as("non esclusivo: solo il titolare").isEqualTo(403);
        assertThat(shared.code()).isEqualTo("EXCLUSIVITY_OWNER_ONLY");
        assertThat(create(owner, event("Condivisa", "19:00", "22:00", false, "NONE", null)).path("exclusive").asBoolean()).isFalse();
        assertThat(staff.postWithCsrf("/api/v1/admin/events", event("Intrusa", "19:00", "22:00", true, "NONE", null), "inventato")
                .status()).isEqualTo(403);
        assertThat(audit("EVENT_CREATED")).isEqualTo(2);
    }

    @Test
    void bozzaValidazioniEPubblicazione() {
        Map<String, Object> noTitle = event("", "19:00", "22:00", true, "NONE", null);
        assertThat(staff.post("/api/v1/admin/events", noTitle).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(staff.post("/api/v1/admin/events", event("Storta", "19:10", "22:00", true, "NONE", null)).code())
                .isEqualTo("NOT_ALIGNED_15_MIN");
        assertThat(staff.post("/api/v1/admin/events", event("Senza quota", "19:00", "22:00", true, "REQUEST", null)).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(staff.post("/api/v1/admin/events", event("Troppi", "19:00", "22:00", true, "REQUEST", 21)).code())
                .as("quota oltre la capienza del locale").isEqualTo("QUOTA_ABOVE_CAPACITY");
        Map<String, Object> untimed = event("Senza data", "19:00", "22:00", true, "NONE", null);
        untimed.remove("date");
        untimed.remove("startTime");
        untimed.remove("endTime");
        JsonNode undated = create(staff, untimed);
        assertThat(publish(staff, undated, false).code()).as("una serata senza data non si pubblica").isEqualTo("EVENT_DATE_REQUIRED");

        JsonNode draft = create(staff, event("DJ set", "19:00", "22:00", true, "REQUEST", 12));
        assertThat(draft.path("slug").asString()).isEqualTo("dj-set");
        assertThat(create(staff, event("DJ set", "19:00", "22:00", true, "REQUEST", 12)).path("slug").asString()).isEqualTo("dj-set-2");
        assertThat(browser().get("/api/v1/public/events").body().toString()).as("le bozze non sono pubbliche").doesNotContain("dj-set");
        assertThat(browser().get("/api/v1/public/events/dj-set").status()).isEqualTo(404);
        assertThat(exclusiveSlots(draft)).isZero();

        ApiClient.Response p = publish(staff, draft, false);
        assertThat(p.status()).as(p.raw()).isEqualTo(200);
        assertThat(p.body().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(publish(staff, p.body(), false).status()).as("doppio clic").isEqualTo(200);
        assertThat(exclusiveSlots(draft)).as("19:00–22:00 = 12 quanti riservati").isEqualTo(12);
        JsonNode pub = browser().get("/api/v1/public/events/dj-set").body();
        assertThat(pub.path("booking").path("open").asBoolean()).isTrue();
        assertThat(pub.path("body").size()).isEqualTo(2);
        assertThat(pub.toString()).as("al pubblico nessun numero di posti").doesNotContain("capacityCovers").doesNotContain("confirmed");

        // il servizio ordinario è bloccato nell'orario dell'evento esclusivo
        assertThat(web(browser(), webBody(DAY, "19:30", 2, nextPhone())).code()).isEqualTo("EVENT_BLOCKS_SLOT");
        assertThat(web(browser(), webBody(DAY, "18:00", 2, nextPhone())).code()).as("18–20 tocca l'evento").isEqualTo("EVENT_BLOCKS_SLOT");
        JsonNode slots = browser().get("/api/v1/public/booking-options?date=" + DAY).body().path("groups").get(0).path("slots");
        assertThat(slots.get(0).path("reason").asString()).isEqualTo("EVENT");
        assertThat(staff.delete("/api/v1/admin/events/" + undated.path("id").asString()).status()).isEqualTo(204);
        assertThat(audit("EVENT_PUBLISHED")).isEqualTo(1);
    }

    // ---- FASE 7, requisito 4: «Visibile sul sito» con un solo salvataggio atomico ----

    private long eventsTitled(String title) {
        return jdbc.sql("SELECT count(*) FROM events WHERE title = ?").param(title).query(Long.class).single();
    }

    private Map<String, Object> visible(Map<String, Object> body, Boolean visible) {
        body.put("visible", visible);
        return body;
    }

    private Map<String, Object> edit(JsonNode event, Map<String, Object> body, Boolean visible) {
        body.put("version", currentVersion(event));
        return visible(body, visible);
    }

    @Test
    void visibileSulSitoSalvataggioAtomico() {
        ApiClient.Response draft = staff.post("/api/v1/admin/events", visible(event("Bozza visibile no", "19:00", "22:00", true, "NONE", null), false));
        assertThat(draft.status()).isEqualTo(201);
        assertThat(draft.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(exclusiveSlots(draft.body())).isZero();

        ApiClient.Response pub = staff.post("/api/v1/admin/events",
                visible(event("Pubblicata subito", "19:00", "22:00", true, "REQUEST", 10), true));
        assertThat(pub.status()).as(pub.raw()).isEqualTo(201);
        assertThat(pub.body().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(exclusiveSlots(pub.body())).isEqualTo(12);
        String id = pub.body().path("id").asString();
        assertThat(staff.get("/api/v1/admin/events/" + id).body().path("status").asString()).as("dopo una nuova lettura").isEqualTo("PUBLISHED");
        ApiClient.Response list = browser().get("/api/v1/public/events");
        assertThat(list.body().toString()).contains("Pubblicata subito").doesNotContain("Bozza visibile no");
        assertThat(list.headers().get("Cache-Control").toString()).as("niente cache: si vede subito").contains("no-cache");

        // stesso orario di un altro evento esclusivo: rifiutato e non resta nemmeno la bozza
        ApiClient.Response clash = staff.post("/api/v1/admin/events", visible(event("Sovrapposta", "20:00", "23:00", true, "NONE", null), true));
        assertThat(clash.code()).isEqualTo("EVENT_CONFLICTS");
        assertThat(eventsTitled("Sovrapposta")).as("nessuna bozza orfana").isZero();
        Map<String, Object> past = visible(event("Nel passato", "19:00", "22:00", true, "NONE", null), true);
        past.put("date", DAY.minusDays(30).toString());
        assertThat(staff.post("/api/v1/admin/events", past).code()).isEqualTo("EVENT_IN_PAST");
        assertThat(eventsTitled("Nel passato")).isZero();

        // modifica senza toccare lo switch: lo stato resta
        Map<String, Object> renamed = edit(pub.body(), event("Pubblicata subito, rinominata", "19:00", "22:00", true, "REQUEST", 10), null);
        assertThat(staff.put("/api/v1/admin/events/" + id, renamed).body().path("status").asString()).isEqualTo("PUBLISHED");
        // bozza → visibile con la modifica
        Map<String, Object> on = edit(draft.body(), event("Bozza visibile no", "23:00", "23:45", true, "NONE", null), true);
        ApiClient.Response turnedOn = staff.put("/api/v1/admin/events/" + draft.body().path("id").asString(), on);
        assertThat(turnedOn.status()).as(turnedOn.raw()).isEqualTo(200);
        assertThat(turnedOn.body().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(audit("EVENT_PUBLISHED")).isEqualTo(2);
    }

    @Test
    void nascondereDalSitoSoloSenzaPrenotazioniAttive() {
        JsonNode ev = published(staff, event("Da nascondere", "19:00", "22:00", true, "REQUEST", 10));
        String id = ev.path("id").asString();
        UUID request = eventRequest(ev, 2);
        ApiClient.Response refused = staff.put("/api/v1/admin/events/" + id, edit(ev, event("Da nascondere", "19:00", "22:00", true, "REQUEST", 10), false));
        assertThat(refused.code()).isEqualTo("EVENT_HAS_ACTIVE_RESERVATIONS");
        assertThat(staff.get("/api/v1/admin/events/" + id).body().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(exclusiveSlots(ev)).isEqualTo(12);

        assertThat(changeStatus(staff, request, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        ApiClient.Response hidden = staff.put("/api/v1/admin/events/" + id, edit(ev, event("Da nascondere", "19:00", "22:00", true, "REQUEST", 10), false));
        assertThat(hidden.status()).as(hidden.raw()).isEqualTo(200);
        assertThat(hidden.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(exclusiveSlots(ev)).as("la riserva esclusiva si libera").isZero();
        assertThat(browser().get("/api/v1/public/events").body().toString()).doesNotContain("Da nascondere");
        assertThat(browser().get("/api/v1/public/events/" + ev.path("slug").asString()).status()).isEqualTo(404);
        assertThat(web(browser(), webBody(DAY, "19:30", 2, nextPhone())).status()).as("il servizio ordinario torna libero").isEqualTo(201);
        assertThat(audit("EVENT_UNPUBLISHED")).isEqualTo(1);

        // con solo storico chiuso non si elimina (resta il riferimento); senza prenotazioni sì, anche se pubblicato
        assertThat(staff.delete("/api/v1/admin/events/" + id).code()).isEqualTo("EVENT_HAS_HISTORY");
        JsonNode other = published(staff, event("Da eliminare", "23:00", "23:45", true, "NONE", null));
        UUID otherRequest = eventRequest(published(staff, event("Con richiesta", "17:00", "18:00", true, "REQUEST", 5)), 2);
        assertThat(staff.delete("/api/v1/admin/events/" + jdbc.sql("SELECT event_id FROM reservations WHERE id = ?")
                .param(otherRequest).query(UUID.class).single()).code()).isEqualTo("EVENT_HAS_ACTIVE_RESERVATIONS");
        assertThat(staff.delete("/api/v1/admin/events/" + other.path("id").asString()).status()).isEqualTo(204);
        assertThat(exclusiveSlots(other)).isZero();
        assertThat(eventsTitled("Da eliminare")).isZero();
    }

    @Test
    void conflittiConLePrenotazioniOrdinarie() {
        UUID confirmed = pending("19:00", 4);
        assertThat(confirm(staff, confirmed).status()).isEqualTo(200);
        JsonNode blocked = create(staff, event("Serata", "20:00", "23:00", true, "NONE", null));
        ApiClient.Response conflict = publish(staff, blocked, true);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("EVENT_CONFLICTS");
        JsonNode context = conflict.body().path("context");
        assertThat(context.path("confirmed").get(0).path("reference").asString()).startsWith("FF-");
        assertThat(context.path("confirmed").get(0).path("guests").asInt()).isEqualTo(4);
        assertThat(context.path("acknowledgeable").asBoolean()).as("confermate: non forzabile").isFalse();
        assertThat(conflict.raw()).as("nessun dato personale nel conflitto").doesNotContain("Cliente di prova").doesNotContain("+39");
        assertThat(exclusiveSlots(blocked)).isZero();

        // annullata l'ordinaria resta una richiesta in attesa: si pubblica solo confermando di averla vista
        assertThat(changeStatus(staff, confirmed, "CANCELLED", Map.of("reason", "VENUE_DECISION")).status()).isEqualTo(200);
        UUID waiting = pending("20:30", 2);
        ApiClient.Response ack = publish(staff, blocked, false);
        assertThat(ack.code()).isEqualTo("EVENT_CONFLICTS");
        assertThat(ack.body().path("context").path("acknowledgeable").asBoolean()).isTrue();
        assertThat(ack.body().path("context").path("pending").size()).isEqualTo(1);
        ApiClient.Response ok = publish(staff, blocked, true);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().path("warnings").toString()).contains("PENDING_REQUESTS_BLOCKED");
        assertThat(confirm(staff, waiting).code()).as("la richiesta ordinaria non si conferma più").isEqualTo("EVENT_BLOCKS_SLOT");

        // un secondo evento esclusivo sovrapposto è un conflitto
        ApiClient.Response other = publish(staff, create(staff, event("Altra", "22:00", "23:30", true, "NONE", null)), true);
        assertThat(other.code()).isEqualTo("EVENT_CONFLICTS");
        assertThat(other.body().path("context").path("otherEvent").asBoolean()).isTrue();
    }

    @Test
    void prenotazioniPerEventoConQuota() {
        JsonNode dj = published(staff, event("Serata DJ", "19:00", "22:00", true, "REQUEST", 6));
        UUID first = eventRequest(dj, 4);
        UUID second = eventRequest(dj, 4);
        Map<String, Object> row = jdbc.sql("SELECT status, event_id, service_window_id FROM reservations WHERE id = ?").param(first)
                .query().singleRow();
        assertThat(row).containsEntry("status", "PENDING").containsEntry("service_window_id", null);
        assertThat(occupied("19:00")).as("in attesa: nessun posto occupato").isZero();

        ApiClient.Response ok = confirm(staff, first);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().path("summary").path("eventTitle").asString()).isEqualTo("Serata DJ");
        assertThat(occupied("19:00")).as("stesso registro della FASE 2").isEqualTo(4);
        assertThat(occupied("21:45")).isEqualTo(4);
        assertThat(confirmedCovers(dj)).isEqualTo(4);
        ApiClient.Response quota = confirm(staff, second);
        assertThat(quota.status()).isEqualTo(409);
        assertThat(quota.code()).isEqualTo("EVENT_QUOTA_EXCEEDED");
        assertThat(status(second)).isEqualTo("PENDING");
        assertThat(confirmedCovers(dj)).isEqualTo(4);

        Map<String, Object> tooMany = webBody(DAY, "00:00", 3, nextPhone());
        tooMany.put("eventId", dj.path("id").asString());
        assertThat(web(browser(), tooMany).code()).as("restano 2 posti nella quota").isEqualTo("EVENT_FULL");

        // persone e orario
        ApiClient.Response moved = staff.patch("/api/v1/admin/reservations/" + first, Map.of("version", version(first), "time", "20:00"));
        assertThat(moved.code()).isEqualTo("EVENT_TIME_FIXED");
        assertThat(staff.patch("/api/v1/admin/reservations/" + first, Map.of("version", version(first), "guests", 6)).status())
                .isEqualTo(200);
        assertThat(confirmedCovers(dj)).isEqualTo(6);
        assertThat(occupied("19:00")).isEqualTo(6);
        assertThat(staff.patch("/api/v1/admin/reservations/" + first, Map.of("version", version(first), "guests", 7)).code())
                .isEqualTo("EVENT_QUOTA_EXCEEDED");

        assertThat(changeStatus(staff, first, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        assertThat(confirmedCovers(dj)).as("quota restituita").isZero();
        assertThat(occupied("19:00")).isZero();
        assertThat(exclusiveSlots(dj)).as("l'evento resta riservato").isEqualTo(12);
        assertThat(confirm(staff, second).status()).isEqualTo(200);

        JsonNode list = staff.get("/api/v1/admin/events/" + dj.path("id").asString()).body();
        assertThat(list.path("confirmedCovers").asInt()).isEqualTo(4);
        assertThat(list.path("confirmedReservations").asInt()).isEqualTo(1);
    }

    @Test
    void eventoNonEsclusivoUsaLoStessoRegistro() {
        UUID ordinary = pending("19:00", 10);
        assertThat(confirm(staff, ordinary).status()).isEqualTo(200);
        ApiClient.Response p = publish(owner, create(owner, event("DJ durante l'aperitivo", "19:00", "21:00", false, "REQUEST", 15)), false);
        assertThat(p.status()).as(p.raw()).isEqualTo(200);
        assertThat(p.body().path("warnings").toString()).as("15 posti di quota, 10 liberi").contains("EVENT_QUOTA_NOT_GUARANTEED");
        JsonNode shared = p.body();
        assertThat(exclusiveSlots(shared)).as("non esclusivo: nessun quanto riservato").isZero();
        assertThat(web(browser(), webBody(DAY, "19:30", 2, nextPhone())).status()).as("il servizio ordinario continua").isEqualTo(201);

        UUID a = eventRequest(shared, 8);
        UUID b = eventRequest(shared, 4);
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(occupied("19:00")).isEqualTo(18);
        ApiClient.Response full = confirm(staff, b);
        assertThat(full.code()).as("quota ok (12 ≤ 15) ma il locale ha 20 posti: 18 + 4 > 20").isEqualTo("CAPACITY_EXCEEDED");
        assertThat(confirmedCovers(shared)).isEqualTo(8);
    }

    @Test
    void annullamentoDellEvento() {
        JsonNode dj = published(staff, event("Da annullare", "19:00", "22:00", true, "REQUEST", 10));
        UUID confirmed = eventRequest(dj, 4);
        UUID waiting = eventRequest(dj, 2);
        assertThat(confirm(staff, confirmed).status()).isEqualTo(200);
        String id = dj.path("id").asString();

        ApiClient.Response denied = staff.post("/api/v1/admin/events/" + id + "/cancel", Map.of("version", currentVersion(dj),
                "cancelReservations", true));
        assertThat(denied.status()).as("con prenotazioni attive: solo il titolare").isEqualTo(403);
        ApiClient.Response cancelled = owner.post("/api/v1/admin/events/" + id + "/cancel", Map.of("version", currentVersion(dj),
                "cancelReservations", true));
        assertThat(cancelled.status()).as(cancelled.raw()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(status(confirmed)).isEqualTo("CANCELLED");
        assertThat(status(waiting)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT status_reason FROM reservations WHERE id = ?").param(confirmed).query(String.class).single())
                .isEqualTo("EVENT_CANCELLED");
        assertThat(occupied("19:00")).isZero();
        assertThat(confirmedCovers(dj)).isZero();
        assertThat(exclusiveSlots(dj)).as("posti restituiti al servizio ordinario").isZero();
        assertThat(outbox(confirmed)).extracting(m -> m.get("template_code")).contains("RESERVATION_CANCELLED");
        JsonNode pub = browser().get("/api/v1/public/events/da-annullare").body();
        assertThat(pub.path("status").asString()).as("visibile come annullato").isEqualTo("CANCELLED");
        assertThat(pub.path("booking").path("open").asBoolean()).isFalse();
        assertThat(web(browser(), webBody(DAY, "19:30", 2, nextPhone())).status()).isEqualTo(201);
        assertThat(audit("EVENT_CANCELLED")).isEqualTo(1);
    }

    @Test
    void annullamentoSenzaToccareLePrenotazioni() {
        JsonNode dj = published(staff, event("Annullato a metà", "19:00", "22:00", true, "REQUEST", 10));
        UUID confirmed = eventRequest(dj, 4);
        assertThat(confirm(staff, confirmed).status()).isEqualTo(200);
        ApiClient.Response cancelled = owner.post("/api/v1/admin/events/" + dj.path("id").asString() + "/cancel",
                Map.of("version", currentVersion(dj), "cancelReservations", false));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(status(confirmed)).as("le prenotazioni restano da gestire").isEqualTo("CONFIRMED");
        assertThat(exclusiveSlots(dj)).as("finché c'è una confermata i quanti restano dell'evento").isEqualTo(12);
        assertThat(changeStatus(staff, confirmed, "CANCELLED", Map.of("reason", "VENUE_DECISION")).status()).isEqualTo(200);
        assertThat(exclusiveSlots(dj)).as("ultima confermata annullata: quanti liberati").isZero();
    }

    @Test
    void modificaDiUnEventoPubblicato() {
        JsonNode dj = published(staff, event("Spostabile", "19:00", "21:00", true, "REQUEST", 10));
        UUID waiting = eventRequest(dj, 2);
        String id = dj.path("id").asString();
        Map<String, Object> later = event("Spostabile", "19:30", "21:30", true, "REQUEST", 10);
        later.put("version", currentVersion(dj));
        ApiClient.Response moved = staff.put("/api/v1/admin/events/" + id, later);
        assertThat(moved.status()).as(moved.raw()).isEqualTo(200);
        assertThat(moved.body().path("startTime").asString()).startsWith("19:30");
        assertThat(exclusiveSlots(dj)).isEqualTo(8);
        assertThat(jdbc.sql("SELECT count(*) FROM capacity_slots WHERE exclusive_event_id IS NOT NULL AND slot_start < ?")
                .param(it.fireflies.support.BookingFixtures.utc(it.fireflies.common.BusinessTime.toInstant(DAY, java.time.LocalTime.of(19, 30))))
                .query(Long.class).single()).as("le 19:00 tornano libere").isZero();
        assertThat(jdbc.sql("SELECT to_char(starts_at AT TIME ZONE 'Europe/Rome', 'HH24:MI') FROM reservations WHERE id = ?")
                .param(waiting).query(String.class).single()).as("la richiesta in attesa segue l'evento").isEqualTo("19:30");

        assertThat(confirm(staff, waiting).status()).isEqualTo(200);
        Map<String, Object> again = event("Spostabile", "20:00", "22:00", true, "REQUEST", 10);
        again.put("version", currentVersion(dj));
        assertThat(staff.put("/api/v1/admin/events/" + id, again).code()).isEqualTo("EVENT_HAS_CONFIRMED_RESERVATIONS");
        Map<String, Object> smaller = event("Spostabile", "19:30", "21:30", true, "REQUEST", 1);
        smaller.put("version", currentVersion(dj));
        assertThat(staff.put("/api/v1/admin/events/" + id, smaller).code()).isEqualTo("CAPACITY_BELOW_CONFIRMED");
        Map<String, Object> shared = event("Spostabile", "19:30", "21:30", false, "REQUEST", 10);
        shared.put("version", currentVersion(dj));
        assertThat(staff.put("/api/v1/admin/events/" + id, shared).code()).isEqualTo("EXCLUSIVITY_OWNER_ONLY");
        Map<String, Object> renamed = event("Spostabile e rinominata", "19:30", "21:30", true, "REQUEST", 12);
        renamed.put("version", currentVersion(dj));
        assertThat(staff.put("/api/v1/admin/events/" + id, renamed).status()).as("titolo e quota si cambiano").isEqualTo(200);
    }

    @Test
    void locandinaEFotoDallaLibreria() {
        String poster = staff.upload("POST", "/api/v1/admin/media", "locandina.jpg", "image/jpeg", TestImages.jpeg(900, 1200),
                Map.of("altText", "Locandina della serata")).body().path("id").asString();
        String photo = staff.upload("POST", "/api/v1/admin/media", "consolle.jpg", "image/jpeg", TestImages.jpeg(1200, 800),
                Map.of("altText", "Consolle")).body().path("id").asString();
        Map<String, Object> body = event("Con locandina", "19:00", "22:00", true, "NONE", null);
        body.put("posterMediaId", poster);
        body.put("mediaIds", List.of(photo, poster));
        published(staff, body);
        JsonNode pub = browser().get("/api/v1/public/events/con-locandina").body();
        assertThat(pub.path("poster").path("alt").asString()).isEqualTo("Locandina della serata");
        assertThat(pub.path("poster").path("srcset").asString()).contains("480w");
        assertThat(pub.path("media").size()).isEqualTo(2);
        ApiClient.Response inUse = staff.delete("/api/v1/admin/media/" + poster);
        assertThat(inUse.code()).as("la locandina in uso non si elimina").isEqualTo("MEDIA_IN_USE");
        body.put("posterMediaId", UUID.randomUUID().toString());
        assertThat(staff.post("/api/v1/admin/events", body).code()).isEqualTo("MEDIA_NOT_FOUND");
    }
}
