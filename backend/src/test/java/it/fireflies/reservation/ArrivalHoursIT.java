package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.dining.ArrivalCatalog;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * FIX 2, branch fasceorarie: gli orari di apertura sono l'unica impostazione del calendario. Le fasce interne
 * (ArrivalRules) valgono per ogni giorno; il sito propone solo gli arrivi che stanno interamente dentro l'apertura.
 * Budget per fascia, capienza contemporanea, eventi esclusivi e duplicati restano quelli di FIX 2.
 */
class ArrivalHoursIT extends ReservationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    ArrivalCatalog catalog;

    private ApiClient owner;
    private ApiClient staff;
    /** Giorno della settimana senza fasce di partenza (configure crea solo quella del giorno di DAY). */
    private int day;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
        day = DAY.plusDays(1).getDayOfWeek().getValue();
        date = DAY.minusDays(5).with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)));
    }

    /** Orari settimanali: per {@code day} apertura e chiusura date (null = chiuso), gli altri giorni 07:00–23:00. */
    private void hours(String opens, String closes) {
        JsonNode current = owner.get("/api/v1/admin/site-settings").body();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            if (d == day && opens == null) {
                rows.add(Map.of("isoDay", d, "closed", true));
            } else {
                rows.add(Map.of("isoDay", d, "closed", false, "opensAt", d == day ? opens : "07:00", "closesAt", d == day ? closes : "23:00"));
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("version", current.path("version").asInt(), "addressLine", "Via di prova, 1 — Martano (LE)",
                "addressConfirmed", false, "mapQuery", "Via di prova Martano", "mapMode", "CONSENT", "hoursConfirmed", true,
                "hours", rows, "confirmClosures", true));
        ApiClient.Response r = owner.put("/api/v1/admin/site-settings", body);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
    }

    /** Arrivi proposti dal sito per la data: «Fascia hh:mm» per ogni orario (disponibile o no). */
    private List<String> arrivals(LocalDate on) {
        JsonNode o = browser().get("/api/v1/public/booking-options?date=" + on + "&guests=2").body();
        List<String> out = new ArrayList<>();
        o.path("groups").valueStream().forEach(g -> g.path("slots").valueStream()
                .forEach(s -> out.add(g.path("name").asString() + " " + s.path("time").asString().substring(0, 5))));
        return out;
    }

    private String reason(LocalDate on) {
        return browser().get("/api/v1/public/booking-options").body().path("unavailableDays").valueStream()
                .filter(d -> d.path("date").asString().equals(on.toString())).map(d -> d.path("reason").asString()).findFirst().orElse(null);
    }

    private static final List<String> FULL_DAY = List.of("Colazione 07:30", "Colazione 08:00", "Colazione 08:30", "Colazione 09:00",
            "Colazione 09:30", "Colazione 10:00", "Colazione 10:30", "Pomeriggio 15:00", "Pomeriggio 16:00", "Pomeriggio 17:00",
            "Aperitivo 18:00", "Aperitivo 18:30", "Aperitivo 19:00", "Aperitivo 19:30", "Aperitivo 20:00", "Aperitivo 20:30",
            "Sera 21:00", "Sera 21:30", "Sera 22:00");

    @Test
    void giornoChiusoApertoRichiusoERiapertoSenzaConfigurareFasce() {
        assertThat(jdbc.sql("SELECT count(*) FROM service_windows WHERE iso_day = ?").param(day).query(Long.class).single()).isZero();
        hours(null, null);
        assertThat(reason(date)).isEqualTo("VENUE_CLOSED");
        assertThat(arrivals(date)).isEmpty();

        hours("07:00", "23:00");
        assertThat(reason(date)).as("aperto: prenotabile senza creare fasce").isNull();
        assertThat(arrivals(date)).as("07:00–23:00: tutti gli arrivi interni").containsExactlyElementsOf(FULL_DAY);
        assertThat(arrivals(date.plusWeeks(4))).as("anche nel mese dopo").containsExactlyElementsOf(FULL_DAY);

        hours(null, null);
        assertThat(reason(date)).isEqualTo("VENUE_CLOSED");
        assertThat(web(browser(), webBody(date, "19:00", 2, nextPhone())).code()).isEqualTo("DAY_CLOSED");

        hours("07:00", "23:00");
        assertThat(reason(date)).isNull();
        ApiClient.Response ok = web(browser(), webBody(date, "19:00", 2, nextPhone()));
        assertThat(ok.status()).as(ok.raw()).isEqualTo(201);
    }

    @Test
    void soloGliArriviCompatibiliConLApertura() {
        hours("18:00", "22:00");
        // Aperitivo 20:30 + 90' = 22:00 sta dentro; Sera 21:30 + 60' = 22:30 no (la durata non viene accorciata)
        assertThat(arrivals(date)).containsExactly("Aperitivo 18:00", "Aperitivo 18:30", "Aperitivo 19:00", "Aperitivo 19:30",
                "Aperitivo 20:00", "Aperitivo 20:30", "Sera 21:00");
        assertThat(web(browser(), webBody(date, "08:00", 2, nextPhone())).code()).as("prima dell'apertura").isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(browser(), webBody(date, "21:30", 2, nextPhone())).code()).as("finirebbe dopo la chiusura").isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(browser(), webBody(date, "21:00", 2, nextPhone())).status()).isEqualTo(201);

        hours("11:00", "14:00");
        assertThat(reason(date)).as("aperto ma nessun arrivo interno nell'orario").isEqualTo("NO_ARRIVAL_TIMES");
        assertThat(arrivals(date)).isEmpty();

        hours("18:00", "02:00");
        assertThat(arrivals(date)).as("chiusura dopo mezzanotte").contains("Aperitivo 20:30", "Sera 22:00").doesNotContain("Colazione 07:30");
    }

    @Test
    void chiusuraEAperturaStraordinarie() {
        hours("07:00", "23:00");
        ApiClient.Response closed = owner.post("/api/v1/admin/opening-exceptions", Map.of("from", date.toString(), "to", date.toString(), "closed", true));
        assertThat(closed.status()).as(closed.raw()).isEqualTo(201);
        assertThat(reason(date)).isEqualTo("VENUE_CLOSED");
        assertThat(web(browser(), webBody(date, "19:00", 2, nextPhone())).code()).isEqualTo("DAY_CLOSED");
        assertThat(reason(date.plusWeeks(1))).as("solo quella data").isNull();

        hours(null, null); // giorno chiuso ogni settimana…
        LocalDate special = date.plusWeeks(2);
        ApiClient.Response open = owner.post("/api/v1/admin/opening-exceptions", Map.of("from", special.toString(), "to", special.toString(),
                "closed", false, "opensAt", "17:00", "closesAt", "23:00"));
        assertThat(open.status()).as(open.raw()).isEqualTo(201);
        assertThat(reason(special)).as("…ma aperto in via straordinaria").isNull();
        assertThat(arrivals(special)).as("con gli orari dell'apertura straordinaria (17:00 + 60' sta dentro)").containsExactly(
                "Pomeriggio 17:00", "Aperitivo 18:00", "Aperitivo 18:30",
                "Aperitivo 19:00", "Aperitivo 19:30", "Aperitivo 20:00", "Aperitivo 20:30", "Sera 21:00", "Sera 21:30", "Sera 22:00");
        assertThat(reason(special.plusWeeks(1))).isEqualTo("VENUE_CLOSED");
    }

    @Test
    void budgetCapienzaEventiEDuplicatiSulleFasceInterne() {
        hours("07:00", "23:00");
        // budget della Sera (capienza 20): 10 + 10 confermate, la terza no anche se fisicamente le prime sono uscite
        UUID a = idOf(web(browser(), webBody(date, "21:00", 10, nextPhone())).body().path("reference").asString());
        UUID b = idOf(web(browser(), webBody(date, "22:00", 10, nextPhone())).body().path("reference").asString());
        webLimiter.clear();
        UUID c = idOf(web(browser(), webBody(date, "21:30", 2, nextPhone())).body().path("reference").asString());
        assertThat(confirm(staff, a).status()).isEqualTo(200);
        assertThat(confirm(staff, b).status()).isEqualTo(200);
        assertThat(confirm(staff, c).code()).isIn("BAND_CAPACITY_EXCEEDED", "CAPACITY_EXCEEDED");
        assertThat(jdbc.sql("SELECT service_band FROM reservations WHERE id = ?").param(a).query(String.class).single()).isEqualTo("SERA");

        // stesso telefono, giorno e fascia: duplicato come in FIX 2
        webLimiter.clear();
        assertThat(web(browser(), webBody(date, "18:00", 2, "333 222 1100")).status()).isEqualTo(201);
        assertThat(web(browser(), webBody(date, "19:30", 2, "333 222 1100")).code()).isEqualTo("DUPLICATE_REQUEST");

        // evento esclusivo nella Colazione: quegli orari diventano «EVENT»
        Map<String, Object> ev = new HashMap<>(Map.of("title", "Colazione privata", "kind", "SERATA", "date", date.toString(),
                "startTime", "08:00", "endTime", "10:00", "exclusive", true, "bookingMode", "NONE"));
        JsonNode created = staff.post("/api/v1/admin/events", ev).body();
        ApiClient.Response pub = staff.put("/api/v1/admin/events/" + created.path("id").asString() + "/visibility",
                Map.of("version", created.path("version").asInt(), "visible", true));
        assertThat(pub.status()).as(pub.raw()).isEqualTo(200);
        JsonNode slots = browser().get("/api/v1/public/booking-options?date=" + date + "&guests=2").body().path("groups").get(0).path("slots");
        assertThat(slots.valueStream().filter(s -> s.path("time").asString().startsWith("08:30")).findFirst().orElseThrow()
                .path("reason").asString()).isEqualTo("EVENT");
    }

    @Test
    void ilCatalogoAggiungeSoloLeFasceMancantiEConservaLoStorico() {
        UUID kept = pending("19:00", 2); // fascia «Aperitivo» personalizzata del giorno di DAY (18:00–21:00 ogni 30', 120')
        String before = jdbc.sql("SELECT service_window_id || '|' || service_band || '|' || starts_at FROM reservations WHERE id = ?")
                .param(kept).query(String.class).single();
        int inserted = catalog.ensureStandardWindows();
        assertThat(inserted).as("4 fasce per ognuno dei 6 giorni che non ne avevano").isEqualTo(24);
        assertThat(catalog.ensureStandardWindows()).as("idempotente").isZero();
        int own = DAY.getDayOfWeek().getValue();
        assertThat(jdbc.sql("SELECT name || ' ' || to_char(first_start, 'HH24:MI') || '-' || to_char(last_start, 'HH24:MI') FROM service_windows WHERE iso_day = ?")
                .param(own).query(String.class).list()).as("fascia personalizzata intatta, nessuna aggiunta").containsExactly("Aperitivo 18:00-21:00");
        assertThat(jdbc.sql("SELECT service_window_id || '|' || service_band || '|' || starts_at FROM reservations WHERE id = ?")
                .param(kept).query(String.class).single()).as("prenotazione esistente invariata").isEqualTo(before);
    }
}
