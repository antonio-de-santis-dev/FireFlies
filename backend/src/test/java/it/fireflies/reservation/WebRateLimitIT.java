package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.Concurrency;

/**
 * FIX-1, req. 4 — limiti del modulo pubblico: chi corregge il modulo o consulta gli orari non viene bloccato; chi
 * insiste a raffica sì, con {@code 429}, {@code Retry-After} e l'attesa scritta nel messaggio. Tutte le richieste dei
 * test arrivano dallo stesso indirizzo (127.0.0.1), come una famiglia dietro lo stesso router.
 */
class WebRateLimitIT extends ReservationTestSupport {

    private ApiClient owner;

    @BeforeEach
    void setUp() {
        owner = signedIn(Role.OWNER);
        configure(owner, 40);
        webLimiter.clear();
    }

    private int requests() {
        return jdbc.sql("SELECT count(*) FROM reservations").query(Integer.class).single();
    }

    @Test
    void tentativiRifiutatiNonConsumanoIlLimiteDelleRichieste() {
        ApiClient client = browser();
        // un cliente reale: orario sbagliato, giorno senza fasce, troppe persone, privacy dimenticata, gruppo troppo grande…
        assertThat(web(client, webBody(DAY, "19:10", 2, nextPhone())).code()).isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(client, webBody(DAY.plusDays(1), "19:00", 2, nextPhone())).code()).isEqualTo("SLOT_NOT_AVAILABLE");
        assertThat(web(client, webBody(DAY, "19:00", 41, nextPhone())).code()).isEqualTo("PARTY_TOO_LARGE");
        Map<String, Object> noPrivacy = new HashMap<>(webBody(DAY, "19:00", 2, nextPhone()));
        noPrivacy.put("privacyAccepted", false);
        for (int i = 0; i < 5; i++) {
            assertThat(web(client, noPrivacy).code()).isEqualTo("VALIDATION_FAILED");
        }
        // …dopo 8 invii rifiutati la richiesta giusta passa (fino alla FASE 8 il sesto tentativo era già bloccato)
        ApiClient.Response ok = web(client, webBody(DAY, "19:00", 2, nextPhone()));
        assertThat(ok.status()).as(ok.raw()).isEqualTo(201);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void reinviiConLaStessaChiaveNonContanoMai() {
        ApiClient client = browser();
        Map<String, Object> body = webBody(DAY, "19:00", 2, nextPhone());
        String key = UUID.randomUUID().toString();
        String reference = null;
        for (int i = 0; i < 15; i++) {
            ApiClient.Response r = client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", key));
            assertThat(r.status()).as("reinvio %d", i).isEqualTo(201);
            reference = reference == null ? r.body().path("reference").asString() : reference;
            assertThat(r.body().path("reference").asString()).isEqualTo(reference);
        }
        assertThat(requests()).isEqualTo(1);
        assertThat(web(client, webBody(DAY, "19:30", 2, nextPhone())).status()).as("un'altra richiesta vera").isEqualTo(201);
    }

    @Test
    void doppioInvioContemporaneoUnaSolaRichiesta() {
        Map<String, Object> body = webBody(DAY, "19:00", 3, nextPhone());
        String key = UUID.randomUUID().toString();
        ApiClient client = browser();
        client.get("/api/v1/auth/csrf");
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                ApiClient.Response r = client.post("/api/v1/public/reservations", body, Map.of("Idempotency-Key", key));
                return r.status() + " " + r.body().path("reference").asString();
            });
        }
        List<String> results = Concurrency.run(tasks).stream().map(r -> r.value()).toList();
        assertThat(results).as("tutti ricevono la stessa ricevuta, nessun errore").allMatch(r -> r.startsWith("201 "));
        assertThat(results.stream().distinct()).hasSize(1);
        assertThat(requests()).isEqualTo(1);
    }

    @Test
    void richiesteCreateOltreIlLimiteOrario429ConAttesa() {
        ApiClient client = browser();
        // FIX 2: 5 richieste create per IP all'ora, la sesta 429
        for (int i = 0; i < 5; i++) {
            assertThat(web(client, webBody(DAY, i % 2 == 0 ? "19:00" : "19:30", 2, nextPhone())).status()).as("richiesta %d", i + 1)
                    .isEqualTo(201);
        }
        ApiClient.Response blocked = web(client, webBody(DAY, "20:00", 2, nextPhone()));
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.code()).isEqualTo("RATE_LIMITED");
        long retry = Long.parseLong(blocked.headers().get("Retry-After").getFirst());
        assertThat(retry).isBetween(3000L, 3600L);
        assertThat(blocked.body().path("detail").asString()).as("attesa scritta nel messaggio").containsPattern("tra (\\d+ minuti|circa 1 ore|circa \\d ore)");
        assertThat(requests()).isEqualTo(5);
    }

    @Test
    void abusoDiTentativiBloccatoAncheConDatiValidi() {
        ApiClient client = browser();
        for (int i = 0; i < 30; i++) {
            assertThat(web(client, webBody(DAY, "12:00", 2, nextPhone())).code()).isEqualTo("SLOT_NOT_AVAILABLE");
        }
        ApiClient.Response blocked = web(client, webBody(DAY, "19:00", 2, nextPhone()));
        assertThat(blocked.status()).as("31° invio in 10 minuti").isEqualTo(429);
        assertThat(blocked.headers()).containsKey("Retry-After");
        assertThat(blocked.body().path("detail").asString()).contains("Troppi tentativi");
        assertThat(requests()).isZero();
    }

    @Test
    void consultareGliOrariNonConsumaGliInvii() {
        ApiClient client = browser();
        for (int i = 0; i < 120; i++) {
            assertThat(client.get("/api/v1/public/booking-options?date=" + DAY + "&guests=" + (1 + i % 6)).status())
                    .as("lettura %d", i + 1).isEqualTo(200);
        }
        ApiClient.Response tooMany = client.get("/api/v1/public/booking-options?date=" + DAY);
        assertThat(tooMany.status()).as("121ª lettura nello stesso minuto").isEqualTo(429);
        assertThat(tooMany.headers()).containsKey("Retry-After");
        assertThat(web(client, webBody(DAY, "19:00", 2, nextPhone())).status()).as("l'invio ha un limite separato").isEqualTo(201);
    }

    @Test
    void nessunDatoPersonaleNeiLogDeiLimiti() {
        String phone = "333 999 8877";
        for (int i = 0; i < 4; i++) {
            web(browser(), webBody(DAY, List.of("18:00", "18:30", "19:00", "19:30").get(i), 2, phone));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE metadata::text LIKE '%9998877%' OR metadata::text LIKE '%finestra%'")
                .query(Integer.class).single()).as("l'audit non contiene telefoni né note").isZero();
    }
}
