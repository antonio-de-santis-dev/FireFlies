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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * FIX 3 — regressioni del sistema prenotazioni con PostgreSQL reale: fascia di un inserimento manuale fuori dagli arrivi
 * (B-04), durata allungata dopo l'arrivo (B-05), paginazione stabile dell'elenco (B-08).
 */
class Fix3ReservationIT extends ReservationTestSupport {

    private ApiClient owner;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20); // «Aperitivo» 18:00–21:00, 2 ore
    }

    private void window(String name, String first, String last, int duration) {
        Map<String, Object> w = new HashMap<>();
        w.put("zoneId", zone);
        w.put("isoDay", DAY.getDayOfWeek().getValue());
        w.put("name", name);
        w.put("firstStart", first);
        w.put("lastStart", last);
        w.put("slotMinutes", 30);
        w.put("defaultDurationMinutes", duration);
        ApiClient.Response r = owner.post("/api/v1/admin/service-windows", w);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
    }

    private ApiClient.Response manual(String time, int guests, boolean confirmNow) {
        Map<String, Object> body = new HashMap<>();
        body.put("source", "PHONE");
        body.put("date", DAY.toString());
        body.put("time", time);
        body.put("guests", guests);
        body.put("name", "Cliente al telefono");
        body.put("phone", nextPhone());
        body.put("confirmNow", confirmNow);
        return staff.post("/api/v1/admin/reservations", body);
    }

    private String band(UUID id) {
        return jdbc.sql("SELECT service_band FROM reservations WHERE id = ?").param(id).query(String.class).single();
    }

    private int bandUsage(String band) {
        return jdbc.sql("SELECT coalesce(sum(confirmed_covers), 0) FROM band_capacity_usage WHERE service_date = ? AND band = ?")
                .params(DAY, band).query(Integer.class).single();
    }

    /**
     * B-04: alle 23:30 nessuna fascia ha un arrivo né copre l'orario. Prima si prendeva la prima fascia del giorno
     * («Colazione»): budget della colazione consumato, quello della sera intatto.
     */
    @Test
    void inserimentoManualeFuoriDagliArriviContaNellaFasciaDellOrario() {
        window("Colazione", "07:30", "10:30", 60);
        window("Sera", "22:00", "22:30", 60); // arrivi fino alle 22:30, coperta fino alle 23:30 escluse
        ApiClient.Response late = manual("23:30", 4, true);
        assertThat(late.status()).as(late.raw()).isEqualTo(201);
        UUID id = UUID.fromString(late.body().path("summary").path("id").asString());
        assertThat(band(id)).isEqualTo("SERA");
        assertThat(bandUsage("SERA")).isEqualTo(4);
        assertThat(bandUsage("COLAZIONE")).as("budget della colazione intatto").isZero();
    }

    /** B-05: clienti che restano oltre l'orario: dopo l'arrivo il gestore allunga la durata (BOOKING_RULES §3.4). */
    @Test
    void dopoLArrivoSiAllungaLaDurataDiUnaConfermata() {
        UUID id = pending("19:00", 2);
        assertThat(confirm(staff, id).status()).isEqualTo(200);
        Instant start = BusinessTime.toInstant(DAY, LocalTime.of(19, 0));
        clock.advance(Duration.between(Instant.now(), start.plus(Duration.ofMinutes(30))));

        Map<String, Object> longer = new HashMap<>();
        longer.put("version", version(id));
        longer.put("durationMinutes", 180);
        ApiClient.Response ok = staff.patch("/api/v1/admin/reservations/" + id, longer);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(occupied("21:30")).as("quarto d'ora aggiunto occupato").isEqualTo(2);

        // una prenotazione non si sposta comunque nel passato, e non si accorcia fino a farla già finire
        Map<String, Object> moved = new HashMap<>();
        moved.put("version", version(id));
        moved.put("time", "18:30");
        assertThat(staff.patch("/api/v1/admin/reservations/" + id, moved).code()).isEqualTo("VALIDATION_FAILED");
        Map<String, Object> shorter = new HashMap<>();
        shorter.put("version", version(id));
        shorter.put("durationMinutes", 15);
        assertThat(staff.patch("/api/v1/admin/reservations/" + id, shorter).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(occupied("21:30")).as("nulla è cambiato").isEqualTo(2);
    }

    /** B-08: righe con stesso stato, orario e momento di creazione: ogni prenotazione compare in una sola pagina. */
    @Test
    void paginazioneStabileConCreazioneSimultanea() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ids.add(UUID.fromString(manual("19:00", 1, false).body().path("summary").path("id").asString()));
        }
        jdbc.sql("UPDATE reservations SET created_at = TIMESTAMPTZ '2026-01-01 10:00+00' WHERE id = ANY (?)")
                .param(ids.toArray(UUID[]::new)).update();
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode items = staff.get("/api/v1/admin/reservations?size=3&page=" + page).body().path("items");
            items.forEach(n -> seen.add(n.path("id").asString()));
        }
        assertThat(seen).hasSize(9).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
    }
}
