package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.ApiException;
import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;
import tools.jackson.databind.JsonNode;

/**
 * FIX 2 — budget di persone confermate per giorno e fascia, duplicati per telefono/giorno/fascia, 5 richieste create per
 * IP all'ora, chiusure settimanali con conferma. Capienza del locale 10, fascia «Aperitivo» 18:00–21:00 (2 ore).
 */
class Fix2BookingIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 10);
        webLimiter.clear();
    }

    private int used() {
        return jdbc.sql("SELECT coalesce(sum(confirmed_covers), 0) FROM band_capacity_usage WHERE service_date = ? AND band = 'APERITIVO'")
                .param(DAY).query(Integer.class).single();
    }

    private UUID pendingFresh(String time, int guests) {
        webLimiter.clear();
        return pending(time, guests);
    }

    // ---- budget della fascia --------------------------------------------------------------------------------

    @Test
    void budgetDellaFasciaConArriviDiversiERicambio() {
        UUID a = pendingFresh("18:00", 6); // 18:00–20:00
        UUID b = pendingFresh("20:00", 4); // 20:00–22:00: fisicamente nessuna sovrapposizione con a
        UUID c = pendingFresh("20:30", 2); // alle 20:30 ci sono solo 4 persone, ma nella fascia già 10
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(confirm(staff, b).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(10);
        ApiClient.Response over = confirm(staff, c);
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.code()).isEqualTo("BAND_CAPACITY_EXCEEDED");
        assertThat(over.body().path("context").path("remaining").asInt()).isZero();
        assertThat(status(c)).isEqualTo("PENDING");
        assertThat(occupied("20:30")).as("posti contemporanei: solo b").isEqualTo(4);

        // il sito non offre più la fascia, anche se fisicamente alle 20:30 ci sarebbe posto
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY + "&guests=1").body();
        assertThat(options.path("groups").get(0).path("slots").valueStream()).allMatch(s -> !s.path("available").asBoolean());
        assertThat(web(browser(), webBody(DAY, "21:00", 1, nextPhone())).code()).isEqualTo("FULLY_BOOKED");

        // annullare una confermata restituisce il budget
        assertThat(changeStatus(staff, a, "CANCELLED", Map.of("reason", "CUSTOMER_REQUEST")).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(4);
        assertThat(confirm(staff, c).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(6);
        // annullare una in attesa o rifiutarla non cambia nulla
        UUID d = pendingFresh("18:30", 3);
        assertThat(changeStatus(staff, d, "REJECTED", Map.of("reason", "NO_CAPACITY")).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(6);
    }

    @Test
    void completataENonPresentataNonRestituisconoIlBudget() {
        UUID done = pendingFresh("18:00", 4);
        UUID absent = pendingFresh("18:00", 3);
        UUID late = pendingFresh("20:30", 3);
        UUID extra = pendingFresh("20:30", 1);
        assertThat(confirm(staff, done).status()).isEqualTo(200);
        assertThat(confirm(staff, absent).status()).isEqualTo(200);
        assertThat(confirm(staff, late).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(10);

        clock.advance(Duration.between(Instant.now(), BusinessTime.toInstant(DAY, LocalTime.of(18, 20))));
        assertThat(changeStatus(staff, done, "COMPLETED", Map.of()).status()).isEqualTo(200);
        assertThat(changeStatus(staff, absent, "NO_SHOW", Map.of()).status()).isEqualTo(200);
        assertThat(changeStatus(staff, done, "COMPLETED", Map.of()).status()).as("cambio ripetuto").isIn(200, 409);
        assertThat(used()).as("completata e non presentata restano nel budget").isEqualTo(10);
        assertThat(occupied("18:45")).as("i posti contemporanei futuri si liberano").isZero();
        assertThat(confirm(staff, extra).code()).isEqualTo("BAND_CAPACITY_EXCEEDED");
        // correzione del titolare tra completata e non presentata: nessun doppio conteggio
        assertThat(changeStatus(owner, absent, "COMPLETED", Map.of()).status()).isEqualTo(200);
        assertThat(used()).isEqualTo(10);
    }

    @Test
    void modificaDiGiornoFasciaEPersoneAggiornaIlBudget() {
        UUID a = pendingFresh("18:00", 6);
        UUID b = pendingFresh("20:00", 3);
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(confirm(staff, b).status()).isEqualTo(200);
        ApiClient.Response bigger = staff.patch("/api/v1/admin/reservations/" + b, new HashMap<>(Map.of("version", version(b), "guests", 5)));
        assertThat(bigger.code()).as("6 + 5 > 10").isEqualTo("BAND_CAPACITY_EXCEEDED");
        assertThat(used()).isEqualTo(9);
        ApiClient.Response ok = staff.patch("/api/v1/admin/reservations/" + b, new HashMap<>(Map.of("version", version(b), "guests", 4)));
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(used()).isEqualTo(10);
        // spostamento a un altro giorno (stessa fascia la settimana dopo): il vecchio giorno torna libero, il nuovo si occupa
        ApiClient.Response moved = staff.patch("/api/v1/admin/reservations/" + b,
                new HashMap<>(Map.of("version", version(b), "date", DAY.plusWeeks(1).toString(), "time", "20:00")));
        assertThat(moved.status()).as(moved.raw()).isEqualTo(200);
        assertThat(used()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT confirmed_covers FROM band_capacity_usage WHERE service_date = ? AND band = 'APERITIVO'")
                .param(DAY.plusWeeks(1)).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void prenotazioniSovrappostePerFasceDiverse() {
        // fascia «Sera» 21:30–22:00 (arrivi) dello stesso giorno: budget proprio, ma stessi posti fisici
        Map<String, Object> w = new HashMap<>(Map.of("zoneId", zone, "isoDay", DAY.getDayOfWeek().getValue(), "name", "Sera",
                "firstStart", "21:30", "lastStart", "22:00", "slotMinutes", 30, "defaultDurationMinutes", 90));
        ApiClient.Response created = owner.post("/api/v1/admin/service-windows", w);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        UUID aperitivo = pendingFresh("21:00", 8); // 21:00–23:00, fascia Aperitivo
        UUID sera = pendingFresh("21:30", 4);      // Sera: budget pieno (10), ma alle 21:30 restano 2 posti
        UUID small = pendingFresh("22:00", 2);
        assertThat(confirm(staff, aperitivo).status()).isEqualTo(200);
        ApiClient.Response physical = confirm(staff, sera);
        assertThat(physical.code()).as("budget della Sera libero, posti contemporanei no").isEqualTo("CAPACITY_EXCEEDED");
        assertThat(confirm(staff, small).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT band || '=' || confirmed_covers FROM band_capacity_usage WHERE service_date = ? ORDER BY band")
                .param(DAY).query(String.class).list()).containsExactly("APERITIVO=8", "SERA=2");
    }

    @Test
    void confermeContemporaneeNonSuperanoIlBudget() {
        List<UUID> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(pendingFresh(i % 2 == 0 ? "18:00" : "20:00", 3)); // 9 posti per turno, budget 10 nella fascia
        }
        ReservationService.Actor actor = new ReservationService.Actor(account(Role.STAFF).account().id(), false);
        List<Callable<ReservationViews.Detail>> tasks = requests.stream()
                .<Callable<ReservationViews.Detail>>map(id -> () -> reservations.changeStatus(actor, id,
                        new ReservationService.StatusChange(version(id), "CONFIRMED", null, null, null, null, false, null)))
                .toList();
        List<Result<ReservationViews.Detail>> results = Concurrency.run(tasks);
        assertThat(results).noneMatch(Result::deadlock);
        assertThat(results).filteredOn(Result::ok).as("3 × 3 = 9 ≤ 10, la quarta sforerebbe").hasSize(3);
        assertThat(results).filteredOn(r -> !r.ok())
                .allSatisfy(r -> assertThat(((ApiException) r.error()).code()).isEqualTo("BAND_CAPACITY_EXCEEDED"));
        assertThat(used()).isEqualTo(9);
    }

    @Test
    void capienzaSottoIlBudgetConfermatoRifiutata() {
        ApiClient.Response set = owner.put("/api/v1/admin/venue-capacity", Map.of("capacity", 10));
        assertThat(set.status()).as(set.raw()).isEqualTo(200);
        UUID a = pendingFresh("18:00", 5);
        UUID b = pendingFresh("20:00", 4);
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(confirm(staff, b).status()).isEqualTo(200);
        ApiClient.Response below = owner.put("/api/v1/admin/venue-capacity", Map.of("capacity", 8));
        assertThat(below.code()).as("posti contemporanei ok (5), ma nella fascia 9 confermate").isEqualTo("CAPACITY_BELOW_CONFIRMED");
        assertThat(below.body().path("context").path("bandConflicts").get(0).path("confirmed").asInt()).isEqualTo(9);
        assertThat(owner.put("/api/v1/admin/venue-capacity", Map.of("capacity", 9)).status()).isEqualTo(200);
    }

    // ---- duplicati --------------------------------------------------------------------------------------------

    @Test
    void unaRichiestaAttivaPerTelefonoGiornoEFascia() {
        ApiClient client = browser();
        assertThat(web(client, webBody(DAY, "18:00", 2, "333 444 5566")).status()).isEqualTo(201);
        ApiClient.Response again = web(client, webBody(DAY, "20:30", 3, "+39 3334445566"));
        assertThat(again.status()).as("altro orario della stessa fascia, telefono scritto diversamente").isEqualTo(409);
        assertThat(again.code()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(again.raw()).as("nessun dato dell'altra prenotazione").doesNotContain("Cliente di prova").doesNotContain("FF-")
                .doesNotContain("18:00").doesNotContain("cliente@example");
        assertThat(web(client, webBody(DAY.plusWeeks(1), "18:00", 2, "333 444 5566")).status()).as("altro giorno").isEqualTo(201);

        UUID first = idOf(jdbc.sql("SELECT public_ref FROM reservations WHERE reservation_date = ? ORDER BY created_at LIMIT 1")
                .param(DAY).query(String.class).single());
        assertThat(changeStatus(staff, first, "REJECTED", Map.of("reason", "NO_CAPACITY")).status()).isEqualTo(200);
        assertThat(web(client, webBody(DAY, "20:30", 3, "333 444 5566")).status()).as("la precedente è rifiutata").isEqualTo(201);
    }

    @Test
    void duplicatiContemporaneiBloccatiDalDatabase() {
        ApiClient client = browser();
        client.get("/api/v1/auth/csrf");
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String time = List.of("18:00", "18:30", "19:00", "19:30", "20:00", "20:30").get(i);
            tasks.add(() -> client.post("/api/v1/public/reservations", webBody(DAY, time, 2, "333 777 8899"),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString())).status());
        }
        List<Integer> statuses = Concurrency.run(tasks).stream().map(Result::value).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).isEqualTo(1);
    }

    // ---- limite per IP ----------------------------------------------------------------------------------------

    @Test
    void cinqueRichiesteCreateAllOraPerIpIRifiutiNonContano() {
        ApiClient client = browser();
        String dup = nextPhone();
        assertThat(web(client, webBody(DAY, "18:00", 1, dup)).status()).isEqualTo(201);
        for (int i = 0; i < 3; i++) {
            assertThat(web(client, webBody(DAY, "19:00", 1, dup)).code()).as("duplicato rifiutato, non conta").isEqualTo("DUPLICATE_REQUEST");
        }
        assertThat(web(client, webBody(DAY, "19:10", 1, nextPhone())).code()).isEqualTo("SLOT_NOT_AVAILABLE");
        for (int i = 0; i < 4; i++) {
            assertThat(web(client, webBody(DAY, "19:30", 1, nextPhone())).status()).as("richiesta %d", i + 2).isEqualTo(201);
        }
        ApiClient.Response sixth = web(client, webBody(DAY, "20:00", 1, nextPhone()));
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.headers()).containsKey("Retry-After");
        assertThat(client.get("/api/v1/public/booking-options?date=" + DAY).status()).as("le letture restano libere").isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).isEqualTo(5);
    }

    // ---- aperture e chiusure settimanali -----------------------------------------------------------------------

    private Map<String, Object> settings(int version, boolean dayClosed, Boolean confirm) {
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            hours.add(d == DAY.getDayOfWeek().getValue() && dayClosed ? Map.of("isoDay", d, "closed", true)
                    : Map.of("isoDay", d, "closed", false, "opensAt", "07:00", "closesAt", "23:00"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("addressLine", "Via di prova, 1 — Martano (LE)");
        body.put("addressConfirmed", false);
        body.put("mapQuery", "Via di prova Martano");
        body.put("mapMode", "CONSENT");
        body.put("hoursConfirmed", true);
        body.put("hours", hours);
        if (confirm != null) {
            body.put("confirmClosures", confirm);
        }
        return body;
    }

    private int settingsVersion() {
        return owner.get("/api/v1/admin/site-settings").body().path("version").asInt();
    }

    private boolean closedOnSite() {
        JsonNode options = browser().get("/api/v1/public/booking-options?date=" + DAY).body();
        return options.path("closedDates").toString().contains(DAY.toString());
    }

    @Test
    void giornoSettimanaleApertoChiusoConConfermaDeiConflitti() {
        assertThat(owner.put("/api/v1/admin/site-settings", settings(settingsVersion(), true, null)).status()).isEqualTo(200);
        assertThat(closedOnSite()).isTrue();
        assertThat(web(browser(), webBody(DAY, "19:00", 2, nextPhone())).code()).as("il backend rivalida").isEqualTo("DAY_CLOSED");

        assertThat(owner.put("/api/v1/admin/site-settings", settings(settingsVersion(), false, null)).status()).isEqualTo(200);
        assertThat(closedOnSite()).as("Chiuso → Aperto: subito prenotabile").isFalse();
        UUID booked = pendingFresh("19:00", 2);

        ApiClient.Response refused = owner.put("/api/v1/admin/site-settings", settings(settingsVersion(), true, null));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("CLOSURE_HAS_RESERVATIONS");
        assertThat(refused.body().path("context").path("conflicts").get(0).path("pending").asInt()).isEqualTo(1);
        assertThat(closedOnSite()).as("nulla salvato").isFalse();
        ApiClient.Response confirmed = owner.put("/api/v1/admin/site-settings", settings(settingsVersion(), true, true));
        assertThat(confirmed.status()).as(confirmed.raw()).isEqualTo(200);
        assertThat(closedOnSite()).isTrue();
        assertThat(status(booked)).as("nessun annullamento automatico").isEqualTo("PENDING");

        // un'apertura straordinaria prevale sull'orario settimanale
        ApiClient.Response special = owner.post("/api/v1/admin/opening-exceptions", Map.of("from", DAY.toString(), "to", DAY.toString(),
                "closed", false, "opensAt", "17:00", "closesAt", "23:00"));
        assertThat(special.status()).as(special.raw()).isEqualTo(201);
        assertThat(closedOnSite()).isFalse();
    }
}
