package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;
import tools.jackson.databind.JsonNode;

/**
 * FASE 7: capienza per giorno e fascia (req. 8) sul registro della FASE 2, riepilogo, calendario di oggi (req. 9),
 * giorni di chiusura settimanali e straordinari (req. 10). PostgreSQL reale, permessi e CSRF.
 */
class OperationsIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;
    private int isoDay;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
        isoDay = DAY.getDayOfWeek().getValue();
    }

    private ApiClient.Response venueCapacity(ApiClient client, int capacity) {
        return client.put("/api/v1/admin/venue-capacity", Map.of("capacity", capacity));
    }

    /** FIX-1: una sola capienza per tutto il locale, la stessa in ogni giorno e fascia, applicata dal registro dei posti. */
    @Test
    void capienzaUnicaDelLocaleSulRegistro() {
        assertThat(venueCapacity(staff, 6).status()).as("solo il titolare").isEqualTo(403);
        assertThat(owner.putWithCsrf("/api/v1/admin/venue-capacity", Map.of("capacity", 6), "inventato").status()).as("CSRF")
                .isEqualTo(403);
        assertThat(venueCapacity(owner, 0).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(venueCapacity(owner, 1001).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(staff.get("/api/v1/admin/capacity-matrix").status()).as("la tabella per giorno e fascia non esiste più")
                .isIn(404, 405);
        ApiClient.Response saved = venueCapacity(owner, 6);
        assertThat(saved.status()).as(saved.raw()).isEqualTo(200);
        assertThat(saved.body().path("physicalCapacity").asInt()).isEqualTo(6);
        assertThat(staff.get("/api/v1/admin/booking-config").body().path("venueCapacity").asInt()).isEqualTo(6);

        // stessa capienza in un'altra fascia dello stesso giorno: nessun valore per fascia da configurare
        Map<String, Object> breakfast = new java.util.HashMap<>(Map.of("zoneId", zone, "isoDay", isoDay, "name", "Colazione",
                "firstStart", "08:00", "lastStart", "10:00", "slotMinutes", 30, "defaultDurationMinutes", 60));
        assertThat(owner.post("/api/v1/admin/service-windows", breakfast).status()).isEqualTo(201);

        UUID first = pending("19:00", 4);
        UUID second = pending("19:00", 4);
        assertThat(confirm(staff, first).status()).isEqualTo(200);
        assertThat(confirm(staff, second).code()).as("oltre le 6 persone del locale").isIn("BAND_CAPACITY_EXCEEDED", "CAPACITY_EXCEEDED");
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY + "&guests=3").body();
        assertThat(options.toString()).contains("FULL");

        ApiClient.Response below = venueCapacity(owner, 3);
        assertThat(below.status()).isEqualTo(409);
        assertThat(below.code()).isEqualTo("CAPACITY_BELOW_CONFIRMED");
        assertThat(below.body().path("context").path("conflicts").get(0).path("occupiedCovers").asInt()).isEqualTo(4);
        assertThat(staff.get("/api/v1/admin/booking-config").body().path("venueCapacity").asInt()).as("non cambia nulla").isEqualTo(6);
        assertThat(audit("BOOKING_CONFIG_UPDATED")).isPositive();

        JsonNode rows = staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY + "&band=APERITIVO").body().path("rows");
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("band").asString()).isEqualTo("APERITIVO");
        assertThat(row.path("requests").asInt()).isEqualTo(2);
        assertThat(row.path("pending").asInt()).isEqualTo(1);
        assertThat(row.path("confirmed").asInt()).isEqualTo(1);
        assertThat(row.path("guests").asInt()).isEqualTo(4);
        assertThat(row.path("capacity").asInt()).isEqualTo(6);
        // FIX 2: fisicamente starebbero 6 persone in un orario della fascia, ma il budget della fascia ne lascia 2 (4 confermate su 6)
        assertThat(row.path("physicalAvailable").asInt()).isEqualTo(6);
        assertThat(row.path("bandCapacity").asInt()).isEqualTo(6);
        assertThat(row.path("bandConfirmed").asInt()).isEqualTo(4);
        assertThat(row.path("bandRemaining").asInt()).isEqualTo(2);
        assertThat(row.path("available").asInt()).as("il gruppo più grande ancora accoglibile").isEqualTo(2);
        JsonNode morning = staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY + "&band=COLAZIONE").body().path("rows");
        assertThat(morning.get(0).path("capacity").asInt()).as("stessa capienza a colazione").isEqualTo(6);
        assertThat(staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY.plusDays(80)).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(browser().get("/api/v1/admin/capacity-summary").status()).isEqualTo(401);
    }

    /** FIX-1, req. 6.4: una settimana alla volta, una fascia: una riga per ogni giorno, anche senza fascia quel giorno. */
    @Test
    void riepilogoSettimanaleDiUnaFascia() {
        LocalDate monday = DAY.with(java.time.DayOfWeek.MONDAY);
        JsonNode week = staff.get("/api/v1/admin/capacity-summary?from=" + monday + "&to=" + monday.plusDays(6) + "&band=APERITIVO")
                .body().path("rows");
        assertThat(week).hasSize(7);
        for (JsonNode r : week) {
            assertThat(r.path("band").asString()).isEqualTo("APERITIVO");
        }
        JsonNode day = java.util.stream.StreamSupport.stream(week.spliterator(), false)
                .filter(r -> r.path("date").asString().equals(DAY.toString())).findFirst().orElseThrow();
        assertThat(day.path("capacity").asInt()).isEqualTo(20);
        assertThat(staff.get("/api/v1/admin/capacity-summary?from=" + monday + "&to=" + monday.plusDays(6) + "&band=MERENDA").code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void capienzaConcorrenteMaiOltreIlLimite() {
        assertThat(venueCapacity(owner, 6).status()).isEqualTo(200);
        List<UUID> requests = List.of(pending("19:00", 4), pending("19:00", 4), pending("19:30", 4), pending("19:00", 3));
        List<Callable<Integer>> tasks = requests.stream().<Callable<Integer>>map(id -> () -> confirm(staff, id).status()).toList();
        long ok = Concurrency.run(tasks).stream().filter(r -> r.ok() && r.value() == 200).count();
        assertThat(ok).as("4+4 o 4+3 superano 6: ne passa una sola").isEqualTo(1);
        assertThat(jdbc.sql("SELECT max(occupied_covers) FROM capacity_slots").query(Integer.class).single()).isLessThanOrEqualTo(6);
    }

    /** Riduzione della capienza e conferme nello stesso momento: mai più persone confermate della capienza salvata. */
    @Test
    void riduzioneDellaCapienzaConConfermeContemporanee() {
        List<UUID> requests = List.of(pending("19:00", 5), pending("19:00", 5), pending("19:00", 5));
        List<Callable<Integer>> tasks = new java.util.ArrayList<>(
                requests.stream().<Callable<Integer>>map(id -> () -> confirm(staff, id).status()).toList());
        tasks.add(() -> venueCapacity(owner, 8).status());
        Concurrency.run(tasks);
        int capacity = jdbc.sql("SELECT physical_capacity FROM capacity_zones WHERE id = ?").param(zone).query(Integer.class).single();
        int occupied = jdbc.sql("SELECT coalesce(max(occupied_covers), 0) FROM capacity_slots").query(Integer.class).single();
        assertThat(occupied).as("confermati entro la capienza finale (%d)", capacity).isLessThanOrEqualTo(capacity);
        assertThat(jdbc.sql("SELECT count(*) FROM capacity_slots WHERE capacity_covers <> ?").param(capacity)
                .query(Integer.class).single()).as("tutti i quanti con la capienza unica").isZero();
    }

    @Test
    void calendarioDiOggiSoloConfermate() {
        LocalDate today = BusinessTime.serviceDate(Instant.now());
        UUID window = jdbc.sql("""
                INSERT INTO service_windows (zone_id, iso_day, name, first_start, last_start, slot_minutes, default_duration_minutes, valid_from)
                VALUES (?, ?, 'Colazione', '08:00', '10:00', 30, 60, ?) RETURNING id
                """).params(zone, today.getDayOfWeek().getValue(), today).query(UUID.class).single();
        UUID breakfast = fixtures.confirm(zone, window, BusinessTime.toInstant(today, LocalTime.of(8, 30)), Duration.ofHours(1), 3);
        jdbc.sql("""
                INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, guest_count,
                                          service_window_id, capacity_zone_id, customer_name, customer_phone_e164)
                VALUES ('PENDOGGI1', 'PENDING', 'PHONE', ?, ?, ?, 2, ?, ?, 'In attesa', '+390000000002')
                """).params(today, BusinessTime.toInstant(today, LocalTime.of(9, 0)).atOffset(java.time.ZoneOffset.UTC),
                BusinessTime.toInstant(today, LocalTime.of(10, 0)).atOffset(java.time.ZoneOffset.UTC), window, zone).update();

        ApiClient.Response r = staff.get("/api/v1/admin/reservations/today");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().get("Cache-Control").toString()).contains("no-store");
        JsonNode body = r.body();
        assertThat(body.path("date").asString()).isEqualTo(today.toString());
        assertThat(body.path("items")).hasSize(1);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("id").asString()).isEqualTo(breakfast.toString());
        assertThat(item.path("time").asString()).startsWith("08:30");
        assertThat(item.path("guests").asInt()).isEqualTo(3);
        assertThat(item.path("band").asString()).isEqualTo("COLAZIONE");
        assertThat(item.path("name").asString()).isEqualTo("Cliente demo");
        assertThat(body.toString()).as("niente telefono").doesNotContain("+39").doesNotContain("In attesa");
        assertThat(staff.get("/api/v1/admin/reservations/today?band=SERA").body().path("items")).isEmpty();
        assertThat(staff.get("/api/v1/admin/reservations/today?band=colazione").body().path("items")).hasSize(1);
        assertThat(staff.get("/api/v1/admin/reservations/today?band=MERENDA").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(browser().get("/api/v1/admin/reservations/today").status()).isEqualTo(401);
    }

    @Test
    void giorniDiChiusuraSettimanaliEStraordinari() {
        jdbc.sql("INSERT INTO opening_hours (iso_day, closed) VALUES (?, true)").param(isoDay).update();
        assertThat(web(browser(), webBody(DAY, "19:00", 2, nextPhone())).code()).as("chiusura settimanale").isEqualTo("DAY_CLOSED");
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY).body();
        assertThat(options.path("closedDates").toString()).contains(DAY.toString());
        assertThat(options.path("closedDates").toString()).as("gli altri giorni senza fasce").contains(DAY.plusDays(1).toString());

        Map<String, Object> opening = Map.of("day", DAY.toString(), "closed", false, "opensAt", "17:00", "closesAt", "23:00",
                "note", "Apertura straordinaria");
        assertThat(staff.post("/api/v1/admin/opening-exceptions", opening).status()).as("solo il titolare").isEqualTo(403);
        ApiClient.Response open = owner.post("/api/v1/admin/opening-exceptions", opening);
        assertThat(open.status()).as(open.raw()).isEqualTo(201);
        assertThat(web(browser(), webBody(DAY, "19:00", 2, nextPhone())).status()).as("apertura straordinaria").isEqualTo(201);
        assertThat(browser().get("/api/v1/public/booking-options?date=" + DAY).body().path("closedDates").toString())
                .doesNotContain(DAY.toString());

        jdbc.sql("DELETE FROM opening_hours").update();
        LocalDate nextWeek = DAY.plusDays(7);
        ApiClient.Response closed = owner.post("/api/v1/admin/opening-exceptions", Map.of("day", nextWeek.toString(), "closed", true));
        assertThat(closed.status()).isEqualTo(201);
        assertThat(closed.body().path("activeReservations").asInt()).isZero();
        assertThat(web(browser(), webBody(nextWeek, "19:00", 2, nextPhone())).code()).as("chiusura straordinaria").isEqualTo("DAY_CLOSED");
        assertThat(owner.post("/api/v1/admin/opening-exceptions", Map.of("day", nextWeek.toString(), "closed", true)).code())
                .isEqualTo("EXCEPTION_OVERLAPS");
        assertThat(owner.delete("/api/v1/admin/opening-exceptions/" + closed.body().path("exception").path("id").asString()).status())
                .isEqualTo(204);
        assertThat(web(browser(), webBody(nextWeek, "19:00", 2, nextPhone())).status()).isEqualTo(201);
        assertThat(owner.post("/api/v1/admin/opening-exceptions", Map.of("day", DAY.minusDays(30).toString(), "closed", true)).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    private ApiClient.Response opening(LocalDate from, LocalDate to, boolean closed) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("from", from.toString(), "to", to.toString(), "closed", closed));
        if (!closed) {
            body.put("opensAt", "17:00");
            body.put("closesAt", "23:00");
        }
        return owner.post("/api/v1/admin/opening-exceptions", body);
    }

    /** FIX-1, req. 6.6: chiusure e aperture su un intervallo di giorni, estremi compresi, senza sovrapposizioni. */
    @Test
    void chiusureEApertureSuUnIntervallo() {
        LocalDate first = DAY.plusDays(7);
        LocalDate last = DAY.plusDays(14); // stesso giorno della settimana della fascia di prova
        ApiClient.Response range = opening(first, last, true);
        assertThat(range.status()).as(range.raw()).isEqualTo(201);
        assertThat(range.body().path("exception").path("endDay").asString()).isEqualTo(last.toString());
        String closedDates = browser().get("/api/v1/public/booking-options").body().path("closedDates").toString();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            assertThat(closedDates).as("chiuso il %s", d).contains(d.toString());
        }
        assertThat(web(browser(), webBody(first, "19:00", 2, nextPhone())).code()).isEqualTo("DAY_CLOSED");
        assertThat(web(browser(), webBody(last, "19:00", 2, nextPhone())).code()).as("ultimo giorno compreso").isEqualTo("DAY_CLOSED");

        assertThat(opening(DAY.plusDays(10), DAY.plusDays(20), false).code()).as("giorni già in un'altra eccezione")
                .isEqualTo("EXCEPTION_OVERLAPS");
        assertThat(opening(DAY.plusDays(30), DAY.plusDays(29), true).code()).as("fine prima dell'inizio").isEqualTo("VALIDATION_FAILED");
        assertThat(opening(DAY.plusDays(30), DAY.plusDays(30 + 400), true).code()).as("al massimo un anno").isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post("/api/v1/admin/opening-exceptions", Map.of("from", DAY.plusDays(30).toString(), "closed", false)).code())
                .as("apertura senza orari").isEqualTo("VALIDATION_FAILED");

        // modifica: l'intervallo si accorcia e l'ultimo giorno torna prenotabile
        String id = range.body().path("exception").path("id").asString();
        ApiClient.Response shorter = owner.put("/api/v1/admin/opening-exceptions/" + id,
                Map.of("from", first.toString(), "to", first.plusDays(1).toString(), "closed", true, "note", "Ferie"));
        assertThat(shorter.status()).as(shorter.raw()).isEqualTo(200);
        assertThat(web(browser(), webBody(last, "19:00", 2, nextPhone())).status()).isEqualTo(201);
        assertThat(staff.put("/api/v1/admin/opening-exceptions/" + id, Map.of("from", first.toString(), "closed", true)).status())
                .as("solo il titolare").isEqualTo(403);
        assertThat(owner.get("/api/v1/admin/opening-exceptions").body().toString()).contains("Ferie");
    }

    /** Una chiusura su giorni con prenotazioni: segnalate con i conteggi, mai cancellate da sole. */
    @Test
    void chiusuraConPrenotazioniEsistentiLeSegnalaSenzaCancellarle() {
        LocalDate day = DAY.plusDays(21);
        UUID confirmed = fixtures.confirm(zone, window, BusinessTime.toInstant(day, LocalTime.of(19, 0)), Duration.ofHours(2), 3);
        // FIX 2: senza conferma esplicita la chiusura non si salva e il gestore vede i giorni coinvolti
        ApiClient.Response refused = opening(day, day.plusDays(1), true);
        assertThat(refused.status()).as(refused.raw()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("CLOSURE_HAS_RESERVATIONS");
        assertThat(refused.body().path("context").path("conflicts").get(0).path("confirmed").asInt()).isEqualTo(1);
        assertThat(refused.body().toString()).as("nessun dato personale").doesNotContain("Cliente demo").doesNotContain("+39");
        assertThat(jdbc.sql("SELECT count(*) FROM opening_exceptions").query(Long.class).single()).isZero();
        ApiClient.Response closed = owner.post("/api/v1/admin/opening-exceptions", Map.of("from", day.toString(),
                "to", day.plusDays(1).toString(), "closed", true, "confirmReservations", true));
        assertThat(closed.status()).as(closed.raw()).isEqualTo(201);
        assertThat(closed.body().path("activeReservations").asInt()).isEqualTo(1);
        JsonNode conflict = closed.body().path("conflicts").get(0);
        assertThat(conflict.path("date").asString()).isEqualTo(day.toString());
        assertThat(conflict.path("confirmed").asInt()).isEqualTo(1);
        assertThat(conflict.path("confirmedGuests").asInt()).isEqualTo(3);
        assertThat(closed.body().toString()).as("nessun dato personale").doesNotContain("Cliente demo").doesNotContain("+39");
        assertThat(jdbc.sql("SELECT status FROM reservations WHERE id = ?").param(confirmed).query(String.class).single())
                .isEqualTo("CONFIRMED");
    }

    /** Due titolari salvano intervalli sovrapposti nello stesso momento: ne resta uno solo (vincolo di esclusione). */
    @Test
    void intervalliContemporaneiNonSiSovrappongono() {
        List<Callable<Integer>> tasks = List.of(
                () -> opening(DAY.plusDays(40), DAY.plusDays(45), true).status(),
                () -> opening(DAY.plusDays(43), DAY.plusDays(50), true).status(),
                () -> opening(DAY.plusDays(44), DAY.plusDays(44), false).status());
        long created = Concurrency.run(tasks).stream().filter(r -> r.ok() && r.value() == 201).count();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM opening_exceptions").query(Integer.class).single()).isEqualTo(1);
    }
}
