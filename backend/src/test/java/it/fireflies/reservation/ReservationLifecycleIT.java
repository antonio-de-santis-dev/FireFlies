package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/** Area gestori: permessi, conferma, rifiuto, annullamento, modifica, capienza, tavoli, inserimento manuale, elenco. */
class ReservationLifecycleIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
    }

    @Test
    void permessiECsrf() {
        UUID id = pending("19:00", 2);
        ApiClient anonymous = browser();
        assertThat(anonymous.get("/api/v1/admin/reservations").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/admin/reservations/" + id).status()).isEqualTo(401);
        assertThat(changeStatus(anonymous, id, "CONFIRMED", Map.of()).status()).isIn(401, 403);
        assertThat(staff.get("/api/v1/admin/reservations").status()).isEqualTo(200);
        assertThat(staff.get("/api/v1/admin/booking-config").status()).as("lo staff legge zone e tavoli").isEqualTo(200);
        assertThat(staff.put("/api/v1/admin/booking-policy", Map.of("webBookingEnabled", false, "minLeadMinutes", 60,
                "maxAdvanceDays", 60, "maxPartySizeWeb", 12, "pendingExpiryMinutesBeforeStart", 60)).status())
                .as("le regole le cambia solo il titolare").isEqualTo(403);
        assertThat(staff.post("/api/v1/admin/tables", Map.of("areaId", UUID.randomUUID(), "code", "X", "minSeats", 1, "maxSeats", 2))
                .status()).isEqualTo(403);

        ApiClient.Response forged = staff.postWithCsrf("/api/v1/admin/reservations", Map.of("source", "PHONE"), "token-inventato");
        assertThat(forged.status()).isEqualTo(403);
        staff.clearCookie("XSRF-TOKEN");
        Map<String, Object> body = Map.of("version", version(id), "status", "CONFIRMED");
        assertThat(staff.patch("/api/v1/admin/reservations/" + id + "/status", body).status())
                .as("senza cookie il client ne chiede uno nuovo: la sessione resta valida").isEqualTo(200);
        assertThat(status(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void confermaOccupaIPostiEPreparaLeNotifiche() {
        UUID id = pending("19:00", 4);
        ApiClient.Response confirmed = confirm(staff, id);

        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        JsonNode detail = confirmed.body();
        assertThat(detail.path("summary").path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(detail.path("handledBy").asString()).isEqualTo("Gestore di prova");
        assertThat(occupied("18:45")).isZero();
        assertThat(occupied("19:00")).isEqualTo(4);
        assertThat(occupied("20:45")).isEqualTo(4);
        assertThat(occupied("21:00")).isZero();
        assertThat(detail.path("history").size()).isEqualTo(2);
        assertThat(detail.path("actions").toString()).contains("CANCEL", "EDIT", "TABLES").doesNotContain("CONFIRM\"");
        // email preparata ma non inviata, SMS non previsto: nessuna notifica risulta consegnata
        List<String> states = outbox(id).stream()
                .map(m -> m.get("template_code") + "/" + m.get("channel") + "/" + m.get("state") + "/" + m.get("skip_reason")).toList();
        assertThat(states).contains("RESERVATION_CONFIRMED/EMAIL/PENDING/null", "RESERVATION_CONFIRMED/SMS/SKIPPED/SMS_DISABLED");
        assertThat(detail.path("notifications").valueStream()).allMatch(n -> !n.path("delivered").asBoolean());
        assertThat(jdbc.sql("SELECT count(*) FROM notification_attempts").query(Long.class).single()).as("nessun invio tentato").isZero();

        ApiClient.Response again = confirm(staff, id);
        assertThat(again.status()).as("doppio clic: nessun effetto").isEqualTo(200);
        assertThat(history(id)).isEqualTo(2);
        assertThat(occupied("19:00")).isEqualTo(4);

        Map<String, Object> stale = Map.of("version", version(id) - 1, "status", "CANCELLED", "reason", "CUSTOMER_REQUEST");
        ApiClient.Response conflict = staff.patch("/api/v1/admin/reservations/" + id + "/status", stale);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void rifiutoEAnnullamento() {
        UUID rejected = pending("19:00", 2);
        assertThat(changeStatus(staff, rejected, "REJECTED", Map.of()).code()).as("motivo obbligatorio").isEqualTo("VALIDATION_FAILED");
        assertThat(changeStatus(staff, rejected, "REJECTED", Map.of("reason", "NO_CAPACITY", "note", "Sala piena")).status()).isEqualTo(200);
        assertThat(status(rejected)).isEqualTo("REJECTED");
        assertThat(confirm(staff, rejected).code()).as("dallo stato finale non si torna indietro").isEqualTo("INVALID_TRANSITION");

        UUID cancelled = pending("19:30", 6);
        assertThat(confirm(staff, cancelled).status()).isEqualTo(200);
        assertThat(occupied("19:30")).isEqualTo(6);
        assertThat(changeStatus(staff, cancelled, "COMPLETED", Map.of()).code()).as("prima dell'arrivo non si completa")
                .isEqualTo("INVALID_TRANSITION");
        assertThat(changeStatus(staff, cancelled, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        assertThat(occupied("19:30")).as("annullando i posti tornano liberi").isZero();
        assertThat(outbox(cancelled)).filteredOn(m -> m.get("template_code").equals("RESERVATION_CONFIRMED") && m.get("channel").equals("EMAIL"))
                .singleElement().satisfies(m -> assertThat(m).containsEntry("state", "SKIPPED").containsEntry("skip_reason", "SUPERSEDED"));
        assertThat(outbox(cancelled)).extracting(m -> m.get("template_code")).contains("RESERVATION_CANCELLED");

        JsonNode detail = staff.get("/api/v1/admin/reservations/" + cancelled).body();
        assertThat(detail.path("history").valueStream().map(h -> h.path("toStatus").asString()).toList())
                .containsExactly("PENDING", "CONFIRMED", "CANCELLED");
        assertThat(detail.path("changes").valueStream().map(c -> c.path("action").asString()).toList())
                .contains("RESERVATION_CREATED", "RESERVATION_STATUS_CHANGED");
        assertThat(detail.path("actions").toString()).contains("NOTES").doesNotContain("CONFIRM");
    }

    @Test
    void capienzaESovrapposizioni() {
        ApiClient small = owner;
        JsonNode locale = small.get("/api/v1/admin/booking-config").body().path("zones").get(0);
        assertThat(small.put("/api/v1/admin/capacity-zones/" + zone, Map.of("version", locale.path("version").asInt(),
                "name", "Locale", "physicalCapacity", 15, "active", true)).status()).isEqualTo(200);

        UUID a = pending("18:00", 10);   // 18:00–20:00
        UUID b = pending("19:00", 10);   // 19:00–21:00: si sovrappone ad a
        UUID c = pending("20:00", 10);   // 20:00–22:00: a è già uscito
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        ApiClient.Response over = confirm(staff, b);
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.code()).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(status(b)).as("nulla cambia se i posti non bastano").isEqualTo("PENDING");
        assertThat(history(b)).isEqualTo(1);
        assertThat(confirm(staff, c).status()).isEqualTo(200);
        assertThat(occupied("19:45")).isEqualTo(10);
        assertThat(occupied("20:00")).isEqualTo(10);

        JsonNode detailB = staff.get("/api/v1/admin/reservations/" + b).body();
        assertThat(detailB.path("capacity").path("fits").asBoolean()).isFalse();
        assertThat(detailB.path("capacity").path("minFree").asInt()).isEqualTo(5);

        JsonNode day = staff.get("/api/v1/admin/capacity?date=" + DAY).body();
        JsonNode quanta = day.path("zones").get(0).path("quanta");
        assertThat(quanta.get(0).path("time").asString()).startsWith("18:00");
        assertThat(quanta.get(0).path("confirmed").asInt()).isEqualTo(10);
        assertThat(quanta.get(4).path("pending").asInt()).as("alle 19 c'è b in attesa").isEqualTo(10);

        // il titolare riduce la capienza sotto i confermati: rifiutato, nessun quanto cambia
        locale = small.get("/api/v1/admin/booking-config").body().path("zones").get(0);
        ApiClient.Response below = small.put("/api/v1/admin/capacity-zones/" + zone, Map.of("version", locale.path("version").asInt(),
                "name", "Locale", "physicalCapacity", 8, "active", true));
        assertThat(below.status()).isEqualTo(409);
        assertThat(below.code()).isEqualTo("CAPACITY_BELOW_CONFIRMED");

        // annullando a resta c (20:00–22:00): b non ci sta ancora; annullando anche c, b ci sta
        assertThat(changeStatus(staff, a, "CANCELLED", Map.of("reason", "VENUE_DECISION")).status()).isEqualTo(200);
        assertThat(confirm(staff, b).code()).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(changeStatus(staff, c, "CANCELLED", Map.of("reason", "VENUE_DECISION")).status()).isEqualTo(200);
        assertThat(confirm(staff, b).status()).as("i posti liberati sono subito disponibili").isEqualTo(200);
        assertThat(occupied("18:00")).isZero();
        assertThat(occupied("20:00")).isEqualTo(10);
    }

    @Test
    void modificaDiUnaConfermata() {
        UUID id = pending("19:00", 4);
        UUID other = pending("19:00", 12);
        assertThat(confirm(staff, id).status()).isEqualTo(200);
        assertThat(confirm(staff, other).status()).isEqualTo(200);

        Map<String, Object> bigger = new HashMap<>(Map.of("version", version(id), "guests", 9));
        ApiClient.Response refused = staff.patch("/api/v1/admin/reservations/" + id, bigger);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(occupied("19:00")).as("nulla cambia").isEqualTo(16);

        Map<String, Object> moved = new HashMap<>(Map.of("version", version(id), "time", "21:00", "guests", 6,
                "internalNote", "Spostati dopo telefonata"));
        ApiClient.Response ok = staff.patch("/api/v1/admin/reservations/" + id, moved);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(occupied("19:00")).isEqualTo(12);
        assertThat(occupied("21:00")).isEqualTo(6);
        assertThat(occupied("22:45")).isEqualTo(6);
        assertThat(ok.body().path("internalNote").asString()).isEqualTo("Spostati dopo telefonata");
        assertThat(outbox(id)).extracting(m -> m.get("template_code")).contains("RESERVATION_UPDATED");
        assertThat(audit("RESERVATION_UPDATED")).isEqualTo(1);

        Map<String, Object> phone = new HashMap<>(Map.of("version", version(id), "phone", "000"));
        assertThat(staff.patch("/api/v1/admin/reservations/" + id, phone).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void tavoliAssegnatiAMano() {
        UUID t2 = table(owner, "T2", 1, 2);
        UUID t4 = table(owner, "T4", 2, 4);
        UUID first = pending("19:00", 4);
        UUID second = pending("20:00", 3);
        assertThat(confirm(staff, first).status()).isEqualTo(200);
        assertThat(confirm(staff, second).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation_table_assignments").query(Long.class).single())
                .as("nessuna assegnazione automatica").isZero();

        ApiClient.Response few = staff.put("/api/v1/admin/reservations/" + first + "/tables", Map.of("version", version(first), "tableIds", List.of(t2)));
        assertThat(few.code()).isEqualTo("SEATS_BELOW_PARTY_SIZE");
        Map<String, Object> override = Map.of("version", version(first), "tableIds", List.of(t2), "overrideSeats", true,
                "overrideReason", "Aggiungiamo due sgabelli");
        assertThat(staff.put("/api/v1/admin/reservations/" + first + "/tables", override).status()).as("solo il titolare").isEqualTo(403);
        ApiClient.Response granted = owner.put("/api/v1/admin/reservations/" + first + "/tables", Map.of("version", version(first),
                "tableIds", List.of(t2), "overrideSeats", true, "overrideReason", "Aggiungiamo due sgabelli"));
        assertThat(granted.status()).as(granted.raw()).isEqualTo(200);
        assertThat(audit("TABLE_SEATS_OVERRIDE")).isEqualTo(1);

        assertThat(staff.put("/api/v1/admin/reservations/" + first + "/tables", Map.of("version", version(first), "tableIds", List.of(t4)))
                .status()).isEqualTo(200);
        ApiClient.Response taken = staff.put("/api/v1/admin/reservations/" + second + "/tables",
                Map.of("version", version(second), "tableIds", List.of(t4)));
        assertThat(taken.status()).as("19–21 e 20–22 sullo stesso tavolo").isEqualTo(409);
        assertThat(taken.code()).isEqualTo("TABLE_CONFLICT");

        assertThat(changeStatus(staff, first, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        assertThat(staff.put("/api/v1/admin/reservations/" + second + "/tables", Map.of("version", version(second), "tableIds", List.of(t4)))
                .status()).as("annullando, il tavolo torna libero").isEqualTo(200);
        JsonNode detail = staff.get("/api/v1/admin/reservations/" + second).body();
        assertThat(detail.path("tables").get(0).path("code").asString()).isEqualTo("T4");
        assertThat(detail.path("summary").path("tables").get(0).asString()).isEqualTo("T4");

        UUID pendingOne = pending("18:00", 2);
        assertThat(staff.put("/api/v1/admin/reservations/" + pendingOne + "/tables", Map.of("version", version(pendingOne),
                "tableIds", List.of(t2))).code()).as("i tavoli solo alle confermate").isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void arrivoAssenzaECorrezioneDelTitolare() {
        UUID done = pending("18:00", 4);
        UUID absent = pending("19:00", 2);
        assertThat(confirm(staff, done).status()).isEqualTo(200);
        assertThat(confirm(staff, absent).status()).isEqualTo(200);

        Instant start = BusinessTime.toInstant(DAY, LocalTime.of(19, 20));
        clock.advance(Duration.between(Instant.now(), start));
        assertThat(changeStatus(staff, done, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).code())
                .as("dopo l'inizio non si annulla").isEqualTo("INVALID_TRANSITION");
        assertThat(changeStatus(staff, done, "COMPLETED", Map.of()).status()).isEqualTo(200);
        assertThat(occupied("19:15")).as("quanto in corso: resta occupato").isEqualTo(6);
        assertThat(occupied("19:30")).as("uscita: i quanti futuri tornano liberi").isEqualTo(2);
        assertThat(changeStatus(staff, absent, "NO_SHOW", Map.of()).status()).isEqualTo(200);
        assertThat(occupied("19:30")).isZero();

        assertThat(changeStatus(staff, absent, "COMPLETED", Map.of()).status()).as("correzione: solo il titolare").isEqualTo(403);
        assertThat(changeStatus(owner, absent, "COMPLETED", Map.of("note", "Erano arrivati in ritardo")).status()).isEqualTo(200);
        assertThat(status(absent)).isEqualTo("COMPLETED");

        clock.advance(Duration.ofHours(49));
        assertThat(changeStatus(owner, absent, "NO_SHOW", Map.of()).code()).as("oltre 48 ore").isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void inserimentoManualeDaTelefono() {
        Map<String, Object> body = new HashMap<>();
        body.put("source", "PHONE");
        body.put("date", DAY.toString());
        body.put("time", "19:15");
        body.put("guests", 5);
        body.put("name", "Cliente al telefono");
        body.put("phone", "0836 000 000");
        body.put("confirmNow", true);
        ApiClient.Response created = staff.post("/api/v1/admin/reservations", body);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        assertThat(created.body().path("summary").path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(created.body().path("summary").path("source").asString()).isEqualTo("PHONE");
        assertThat(created.body().path("warnings").toString()).as("19:15 non è un arrivo della fascia").contains("OUTSIDE_SLOTS");
        assertThat(created.body().path("createdBy").asString()).isEqualTo("Gestore di prova");
        assertThat(occupied("19:15")).isEqualTo(5);

        body.remove("phone");
        assertThat(staff.post("/api/v1/admin/reservations", body).code()).as("telefono obbligatorio").isEqualTo("VALIDATION_FAILED");
        body.put("phone", "0836 000 001");
        body.put("source", "WEB");
        assertThat(staff.post("/api/v1/admin/reservations", body).code()).as("WEB solo dal sito").isEqualTo("VALIDATION_FAILED");
        body.put("source", "EMAIL");
        body.put("date", DAY.plusDays(1).toString());
        assertThat(staff.post("/api/v1/admin/reservations", body).code()).isEqualTo("NO_SERVICE_WINDOW");
        body.put("date", DAY.toString());
        body.put("time", "19:10");
        assertThat(staff.post("/api/v1/admin/reservations", body).code()).isEqualTo("NOT_ALIGNED_15_MIN");
        body.put("time", "19:00");
        body.put("guests", 16);
        ApiClient.Response full = staff.post("/api/v1/admin/reservations", body);
        assertThat(full.code()).isEqualTo("CAPACITY_EXCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).as("nessuna riga a metà").isEqualTo(1);
        body.put("confirmNow", false);
        body.put("needsCallback", true);
        ApiClient.Response waiting = staff.post("/api/v1/admin/reservations", body);
        assertThat(waiting.status()).isEqualTo(201);
        assertThat(waiting.body().path("summary").path("status").asString()).isEqualTo("PENDING");
        assertThat(waiting.body().path("summary").path("needsCallback").asBoolean()).isTrue();
    }

    private boolean callback(UUID id) {
        return jdbc.sql("SELECT needs_callback FROM reservations WHERE id = ?").param(id).query(Boolean.class).single();
    }

    private UUID flagged(String time) {
        UUID id = pending(time, 2);
        ApiClient.Response r = staff.patch("/api/v1/admin/reservations/" + id, Map.of("version", version(id), "needsCallback", true));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(callback(id)).isTrue();
        return id;
    }

    /** FASE 7, req. 5: «Da richiamare» si spegne nel backend quando la richiesta esce da «In attesa». */
    @Test
    void daRichiamareSiSpegneAllaConfermaEAllaChiusura() {
        UUID confirmed = flagged("18:00");
        assertThat(confirm(staff, confirmed).status()).isEqualTo(200);
        assertThat(callback(confirmed)).as("confermata").isFalse();
        UUID declined = flagged("18:30");
        assertThat(changeStatus(staff, declined, "REJECTED", Map.of("reason", "NO_CAPACITY")).status()).isEqualTo(200);
        assertThat(callback(declined)).as("rifiutata").isFalse();
        UUID cancelled = flagged("19:00");
        assertThat(changeStatus(staff, cancelled, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        assertThat(callback(cancelled)).as("annullata").isFalse();
        UUID still = flagged("19:30");
        JsonNode list = staff.get("/api/v1/admin/reservations?needsCallback=true").body().path("items");
        assertThat(list).as("nel filtro resta solo quella ancora in attesa").hasSize(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo(still.toString());

        Map<String, Object> manual = new HashMap<>();
        manual.put("source", "PHONE");
        manual.put("date", DAY.toString());
        manual.put("time", "20:00");
        manual.put("guests", 2);
        manual.put("name", "Cliente richiamato");
        manual.put("phone", "0836 000 002");
        manual.put("confirmNow", true);
        manual.put("needsCallback", true);
        ApiClient.Response now = staff.post("/api/v1/admin/reservations", manual);
        assertThat(now.status()).as(now.raw()).isEqualTo(201);
        assertThat(now.body().path("summary").path("needsCallback").asBoolean()).as("inserita già confermata").isFalse();
    }

    @Test
    void elencoConFiltriEConteggi() {
        UUID a = pending("18:00", 2);
        UUID b = pending("19:00", 3);
        pending("20:00", 4);
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(changeStatus(staff, b, "REJECTED", Map.of("reason", "OTHER")).status()).isEqualTo(200);

        JsonNode all = staff.get("/api/v1/admin/reservations?from=" + DAY + "&to=" + DAY).body();
        assertThat(all.path("total").asInt()).isEqualTo(3);
        assertThat(all.path("items").get(0).path("status").asString()).as("le richieste in attesa in cima").isEqualTo("PENDING");
        assertThat(all.path("counts").path("PENDING").asInt()).isEqualTo(1);
        assertThat(all.path("counts").path("CONFIRMED").asInt()).isEqualTo(1);
        assertThat(all.path("counts").path("REJECTED").asInt()).isEqualTo(1);

        JsonNode confirmed = staff.get("/api/v1/admin/reservations?status=CONFIRMED").body();
        assertThat(confirmed.path("total").asInt()).isEqualTo(1);
        assertThat(confirmed.path("counts").path("PENDING").asInt()).as("i conteggi ignorano il filtro di stato").isEqualTo(1);
        assertThat(staff.get("/api/v1/admin/reservations?source=PHONE").body().path("total").asInt()).isZero();
        assertThat(staff.get("/api/v1/admin/reservations?from=" + DAY.plusDays(1)).body().path("total").asInt()).isZero();
        assertThat(staff.get("/api/v1/admin/reservations?q=300%20000").body().path("total").asInt()).isEqualTo(3);
        assertThat(staff.get("/api/v1/admin/reservations?size=2").body().path("items").size()).isEqualTo(2);
        assertThat(staff.get("/api/v1/admin/reservations/" + UUID.randomUUID()).status()).isEqualTo(404);
    }
}
