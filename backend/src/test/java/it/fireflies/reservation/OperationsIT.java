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

    private ApiClient.Response saveCell(ApiClient client, String band, Integer max) {
        java.util.HashMap<String, Object> cell = new java.util.HashMap<>();
        cell.put("isoDay", isoDay);
        cell.put("band", band);
        cell.put("maxCovers", max);
        return client.put("/api/v1/admin/capacity-matrix", Map.of("cells", List.of(cell)));
    }

    private JsonNode cell(JsonNode matrix, String band) {
        for (JsonNode c : matrix.path("cells")) {
            if (c.path("isoDay").asInt() == isoDay && c.path("band").asString().equals(band)) {
                return c;
            }
        }
        throw new AssertionError("cella mancante");
    }

    @Test
    void capienzaPerGiornoEFasciaSulRegistro() {
        JsonNode matrix = staff.get("/api/v1/admin/capacity-matrix").body();
        assertThat(cell(matrix, "APERITIVO").path("offered").asBoolean()).isTrue();
        assertThat(cell(matrix, "APERITIVO").path("maxCovers").isNull()).as("senza limite = capienza del locale").isTrue();
        assertThat(cell(matrix, "COLAZIONE").path("offered").asBoolean()).isFalse();
        assertThat(saveCell(staff, "APERITIVO", 6).status()).as("solo il titolare").isEqualTo(403);
        assertThat(owner.postWithCsrf("/api/v1/admin/opening-exceptions", Map.of("day", DAY.toString(), "closed", true), "inventato")
                .status()).as("CSRF").isEqualTo(403);
        assertThat(saveCell(owner, "APERITIVO", -1).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(saveCell(owner, "MERENDA", 5).code()).isEqualTo("VALIDATION_FAILED");
        ApiClient.Response saved = saveCell(owner, "APERITIVO", 6);
        assertThat(saved.status()).as(saved.raw()).isEqualTo(200);
        assertThat(cell(saved.body(), "APERITIVO").path("maxCovers").asInt()).isEqualTo(6);

        UUID first = pending("19:00", 4);
        UUID second = pending("19:00", 4);
        assertThat(confirm(staff, first).status()).isEqualTo(200);
        assertThat(confirm(staff, second).code()).as("oltre le 6 persone della fascia").isEqualTo("CAPACITY_EXCEEDED");
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY + "&guests=3").body();
        assertThat(options.toString()).contains("FULL");

        ApiClient.Response below = saveCell(owner, "APERITIVO", 3);
        assertThat(below.status()).isEqualTo(409);
        assertThat(below.code()).isEqualTo("CAPACITY_BELOW_CONFIRMED");
        assertThat(cell(staff.get("/api/v1/admin/capacity-matrix").body(), "APERITIVO").path("maxCovers").asInt())
                .as("non cambia nulla").isEqualTo(6);
        assertThat(audit("BOOKING_CONFIG_UPDATED")).isPositive();

        JsonNode rows = staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY).body().path("rows");
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("band").asString()).isEqualTo("APERITIVO");
        assertThat(row.path("requests").asInt()).isEqualTo(2);
        assertThat(row.path("pending").asInt()).isEqualTo(1);
        assertThat(row.path("confirmed").asInt()).isEqualTo(1);
        assertThat(row.path("guests").asInt()).isEqualTo(4);
        assertThat(row.path("capacity").asInt()).isEqualTo(6);
        assertThat(row.path("available").asInt()).as("il gruppo più grande ancora accoglibile").isEqualTo(6);
        assertThat(staff.get("/api/v1/admin/capacity-summary?from=" + DAY + "&to=" + DAY.plusDays(80)).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(browser().get("/api/v1/admin/capacity-summary").status()).isEqualTo(401);
    }

    @Test
    void capienzaConcorrenteMaiOltreIlLimite() {
        assertThat(saveCell(owner, "APERITIVO", 6).status()).isEqualTo(200);
        List<UUID> requests = List.of(pending("19:00", 4), pending("19:00", 4), pending("19:30", 4), pending("19:00", 3));
        List<Callable<Integer>> tasks = requests.stream().<Callable<Integer>>map(id -> () -> confirm(staff, id).status()).toList();
        long ok = Concurrency.run(tasks).stream().filter(r -> r.ok() && r.value() == 200).count();
        assertThat(ok).as("4+4 o 4+3 superano 6: ne passa una sola").isEqualTo(1);
        assertThat(jdbc.sql("SELECT max(occupied_covers) FROM capacity_slots").query(Integer.class).single()).isLessThanOrEqualTo(6);
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
                .isEqualTo("EXCEPTION_EXISTS");
        assertThat(owner.delete("/api/v1/admin/opening-exceptions/" + closed.body().path("exception").path("id").asString()).status())
                .isEqualTo(204);
        assertThat(web(browser(), webBody(nextWeek, "19:00", 2, nextPhone())).status()).isEqualTo(201);
        assertThat(owner.post("/api/v1/admin/opening-exceptions", Map.of("day", DAY.minusDays(30).toString(), "closed", true)).code())
                .isEqualTo("VALIDATION_FAILED");
    }
}
