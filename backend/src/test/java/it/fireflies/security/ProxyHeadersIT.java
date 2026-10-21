package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.support.ApiClient;

/**
 * FASE 8 (V-01): IP del client dietro il reverse proxy con la stessa configurazione di {@code application-prod.yml}
 * (RemoteIpValve, proxy fidati = reti private e loopback). Un client non può aggirare il limite per IP mettendo un
 * {@code X-Forwarded-For} falso davanti a quello aggiunto dal proxy. Più gli header di sicurezza.
 */
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        // formato .properties: ogni barra della regex va raddoppiata
        "server.tomcat.remoteip.internal-proxies=127\\\\.\\\\d{1,3}\\\\.\\\\d{1,3}\\\\.\\\\d{1,3}|0:0:0:0:0:0:0:1",
        "server.tomcat.remoteip.remote-ip-header=x-forwarded-for",
        "server.tomcat.remoteip.protocol-header=x-forwarded-proto"})
class ProxyHeadersIT extends ReservationTestSupport {

    @BeforeEach
    void setUp() {
        configure(signedIn(Role.OWNER), 40);
        webLimiter.clear();
    }

    /** Richiesta come la inoltra il proxy: X-Forwarded-For già scritto dal client + l'IP vero aggiunto in coda. */
    private ApiClient.Response viaProxy(String forwardedFor) {
        return browser().post("/api/v1/public/reservations", webBody(DAY, "19:00", 2, nextPhone()),
                Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString(), "X-Forwarded-For", forwardedFor,
                        "X-Forwarded-Proto", "https"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.boot.web.server.context.WebServerApplicationContext web;

    @Test
    void valvolaInstallata() {
        var tomcat = ((org.springframework.boot.tomcat.TomcatWebServer) web.getWebServer()).getTomcat();
        var valves = java.util.Arrays.stream(tomcat.getEngine().getPipeline().getValves()).map(v -> v.getClass().getSimpleName()).toList();
        assertThat(valves).contains("RemoteIpValve");
    }

    @Test
    void xForwardedForFalsoNonAggiraIlLimitePerIp() {
        for (int i = 1; i <= 5; i++) { // FIX 2: 5 richieste create all'ora per indirizzo
            assertThat(viaProxy("10.9.9." + i + ", 203.0.113.9").status()).as("richiesta %d", i).isEqualTo(201);
        }
        ApiClient.Response sixth = viaProxy("10.9.9.99, 203.0.113.9");
        assertThat(sixth.status()).as("stesso IP vero, prefisso falso diverso").isEqualTo(429);
        assertThat(sixth.code()).isEqualTo("RATE_LIMITED");
        assertThat(viaProxy("203.0.113.10").status()).as("un altro client ha il suo limite").isEqualTo(201);
    }

    @Test
    void headerDiSicurezzaSulleRisposteHttps() {
        ApiClient.Response r = browser().get("/api/v1/public/booking-options", Map.of("X-Forwarded-Proto", "https"));
        assertThat(r.headers().get("Strict-Transport-Security").toString()).contains("max-age=31536000").contains("includeSubDomains");
        assertThat(r.headers().get("Content-Security-Policy").toString()).contains("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(r.headers().get("X-Content-Type-Options").toString()).contains("nosniff");
        assertThat(r.headers().get("Referrer-Policy").toString()).contains("no-referrer");
        assertThat(r.headers().get("Permissions-Policy").toString()).contains("camera=()");
        assertThat(r.headers().get("X-Frame-Options").toString()).contains("DENY");
        assertThat(r.headers()).doesNotContainKey("Access-Control-Allow-Origin");
    }
}
