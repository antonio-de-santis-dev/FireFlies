package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Audit del calendario (FIX 2): un giorno della settimana riaperto negli orari torna subito prenotabile, senza riavvio;
 * locale chiuso e fasce chiuse agli arrivi sono motivi distinti, un giorno aperto ha sempre le fasce interne; le date straordinarie
 * prevalgono. Verificato per tutti e sette i giorni (lunedì = 1 … domenica = 7).
 */
class CalendarOpeningIT extends ReservationTestSupport {

    private ApiClient owner;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        configure(owner, 20); // fascia «Aperitivo» solo nel giorno della settimana di DAY
    }

    private void windowOn(int isoDay) {
        Map<String, Object> w = new HashMap<>(Map.of("zoneId", zone, "isoDay", isoDay, "name", "Sera", "firstStart", "21:00",
                "lastStart", "22:00", "slotMinutes", 30, "defaultDurationMinutes", 60));
        ApiClient.Response r = owner.post("/api/v1/admin/service-windows", w);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    /** Salva gli orari settimanali con i giorni indicati chiusi (gli altri aperti 07:00–23:00), come il pannello. */
    private ApiClient.Response hours(Set<Integer> closed) {
        JsonNode current = owner.get("/api/v1/admin/site-settings").body();
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            hours.add(closed.contains(d) ? Map.of("isoDay", d, "closed", true)
                    : Map.of("isoDay", d, "closed", false, "opensAt", "07:00", "closesAt", "23:00"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("version", current.path("version").asInt());
        body.put("addressLine", "Via di prova, 1 — Martano (LE)");
        body.put("addressConfirmed", false);
        body.put("mapQuery", "Via di prova Martano");
        body.put("mapMode", "CONSENT");
        body.put("hoursConfirmed", true);
        body.put("hours", hours);
        body.put("confirmClosures", true);
        return owner.put("/api/v1/admin/site-settings", body);
    }

    private JsonNode options() {
        return browser().get("/api/v1/public/booking-options").body();
    }

    /** Motivo per cui la data non è prenotabile, oppure null se è selezionabile nel calendario. */
    private static String reason(JsonNode options, LocalDate date) {
        boolean closed = options.path("closedDates").valueStream().anyMatch(d -> d.asString().equals(date.toString()));
        String why = options.path("unavailableDays").valueStream().filter(d -> d.path("date").asString().equals(date.toString()))
                .map(d -> d.path("reason").asString()).findFirst().orElse(null);
        assertThat(why != null).as("closedDates e unavailableDays coerenti per %s", date).isEqualTo(closed);
        return why;
    }

    /** Prossima data (da domani, entro i giorni prenotabili) con quel giorno della settimana. */
    private static LocalDate next(int isoDay) {
        return DAY.minusDays(6).with(TemporalAdjusters.nextOrSame(DayOfWeek.of(isoDay)));
    }

    @Test
    void ogniGiornoChiusoRiapertoERichiusoSenzaRiavvio() {
        for (int d = 1; d <= 7; d++) {
            if (d != DAY.getDayOfWeek().getValue()) {
                windowOn(d); // fasce in tutti i giorni
            }
        }
        for (int d = 1; d <= 7; d++) {
            LocalDate date = next(d);
            assertThat(date.getDayOfWeek().getValue()).isEqualTo(d);
            assertThat(hours(Set.of(d)).status()).isEqualTo(200);
            JsonNode closed = options();
            assertThat(reason(closed, date)).as("giorno %d chiuso", d).isEqualTo("VENUE_CLOSED");
            assertThat(reason(closed, date.plusWeeks(1))).as("anche la settimana dopo").isEqualTo("VENUE_CLOSED");
            assertThat(reason(closed, date.plusDays(1))).as("il giorno dopo resta aperto").isNull();

            assertThat(hours(Set.of()).status()).isEqualTo(200);
            JsonNode open = options();
            assertThat(reason(open, date)).as("giorno %d riaperto: subito selezionabile", d).isNull();
            assertThat(reason(open, date.plusWeeks(4))).as("anche nel mese successivo").isNull();
            JsonNode slots = browser().get("/api/v1/public/booking-options?date=" + date + "&guests=2").body().path("groups");
            assertThat(slots.valueStream().flatMap(g -> g.path("slots").valueStream()).anyMatch(s -> s.path("available").asBoolean()))
                    .as("giorno %d riaperto: orari prenotabili", d).isTrue();
        }
    }

    @Test
    void giornoApertoSenzaFasceDiventaPrenotabileDaSolo() {
        // fasceorarie: le fasce non si configurano più; salvando gli orari il sistema aggiunge quelle interne
        int withoutWindows = DAY.plusDays(1).getDayOfWeek().getValue(); // configure crea la fascia solo nel giorno di DAY
        assertThat(jdbc.sql("SELECT count(*) FROM service_windows WHERE iso_day = ?").param(withoutWindows).query(Long.class).single()).isZero();
        assertThat(hours(Set.of()).status()).isEqualTo(200);
        LocalDate date = next(withoutWindows);
        assertThat(reason(options(), date)).as("aperto: subito prenotabile, senza configurare fasce").isNull();
        JsonNode rows = owner.get("/api/v1/admin/capacity-summary?from=" + date + "&to=" + date + "&band=SERA").body().path("rows");
        assertThat(rows.get(0).path("closed").asBoolean()).isFalse();
        assertThat(rows.get(0).path("capacity").isNull()).as("la Sera interna è prevista").isFalse();
    }

    @Test
    void chiusuraStraordinariaPrevaleEPoiSiRimuove() {
        assertThat(hours(Set.of()).status()).isEqualTo(200);
        ApiClient.Response closure = owner.post("/api/v1/admin/opening-exceptions", Map.of("from", DAY.toString(), "to", DAY.toString(),
                "closed", true));
        assertThat(closure.status()).as(closure.raw()).isEqualTo(201);
        JsonNode o = options();
        assertThat(reason(o, DAY)).isEqualTo("VENUE_CLOSED");
        assertThat(reason(o, DAY.plusWeeks(1))).as("solo la data interessata").isNull();
        assertThat(owner.delete("/api/v1/admin/opening-exceptions/" + closure.body().path("exception").path("id").asString()).status())
                .isEqualTo(204);
        assertThat(reason(options(), DAY)).as("rimossa la chiusura: di nuovo selezionabile").isNull();

        // una fascia chiusa agli arrivi in quella data: motivo distinto dalla chiusura del locale
        ApiClient.Response band = owner.post("/api/v1/admin/booking-exceptions", Map.of("serviceDate", DAY.toString(), "kind", "CLOSED",
                "serviceWindowId", window.toString()));
        assertThat(band.status()).as(band.raw()).isEqualTo(201);
        assertThat(reason(options(), DAY)).isEqualTo("ARRIVALS_CLOSED");
    }

    @Test
    void prenotazioneInUnGiornoRiapertoAccettata() {
        int d = DAY.getDayOfWeek().getValue();
        assertThat(hours(Set.of(d)).status()).isEqualTo(200);
        assertThat(web(browser(), webBody(DAY, "19:00", 2, nextPhone())).code()).as("chiuso: il backend rifiuta").isEqualTo("DAY_CLOSED");
        assertThat(hours(Set.of()).status()).isEqualTo(200);
        ApiClient.Response ok = web(browser(), webBody(DAY, "19:00", 2, nextPhone()));
        assertThat(ok.status()).as(ok.raw()).isEqualTo(201);
        assertThat(ok.body().path("status").asString()).isEqualTo("PENDING");
        // richiuderlo con una richiesta attiva chiede conferma (FIX 2) e non la tocca
        ApiClient.Response refuse = hoursWithoutConfirm(Set.of(d));
        assertThat(refuse.code()).isEqualTo("CLOSURE_HAS_RESERVATIONS");
        assertThat(reason(options(), DAY)).as("nulla salvato: resta aperto").isNull();
    }

    private ApiClient.Response hoursWithoutConfirm(Set<Integer> closed) {
        JsonNode current = owner.get("/api/v1/admin/site-settings").body();
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            hours.add(closed.contains(d) ? Map.of("isoDay", d, "closed", true)
                    : Map.of("isoDay", d, "closed", false, "opensAt", "07:00", "closesAt", "23:00"));
        }
        Map<String, Object> body = new HashMap<>(Map.of("version", current.path("version").asInt(), "addressLine",
                "Via di prova, 1 — Martano (LE)", "addressConfirmed", false, "mapQuery", "Via di prova Martano", "mapMode", "CONSENT",
                "hoursConfirmed", true, "hours", hours));
        return owner.put("/api/v1/admin/site-settings", body);
    }
}
