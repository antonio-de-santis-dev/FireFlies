package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adattatore della Cloud API contro un server HTTP locale che imita Meta (nessuna chiamata a Meta, nessun token reale):
 * forma della richiesta, numeri di prova, errori 131031/190/132001/limiti/5xx, timeout, connessione rifiutata.
 */
class MetaCloudWhatsAppGatewayTest {

    /** Numeri finti (prefisso 39, serie 000): non appartengono a nessuno. */
    static final String ALLOWED = "+390000000001";
    static final String OTHER = "+390000000002";
    static final String FAKE_TOKEN = "TEST-TOKEN-NON-REALE";

    private final ObjectMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicReference<Responder> responder = new AtomicReference<>();

    interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            paths.add(exchange.getRequestURI().getPath());
            responder.get().respond(exchange);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String error(int code, Integer subcode) {
        return "{\"error\":{\"message\":\"messaggio con +390000000001\",\"type\":\"OAuthException\",\"code\":" + code
                + (subcode == null ? "" : ",\"error_subcode\":" + subcode) + ",\"fbtrace_id\":\"x\"}}";
    }

    static WhatsAppProperties config(URI base, Duration requestTimeout) {
        return new WhatsAppProperties(WhatsAppProperties.Mode.META_TEST, FAKE_TOKEN, "1234567890", null, "v21.0",
                ALLOWED + ", 39 000 000 0009", "verify", "secret", base, "fireflies_prenotazione_confermata", "it",
                Duration.ofSeconds(2), requestTimeout);
    }

    private MetaCloudWhatsAppGateway gateway(Duration requestTimeout) {
        WhatsAppProperties c = config(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), requestTimeout);
        return new MetaCloudWhatsAppGateway(c, HttpClient.newBuilder().connectTimeout(c.connectTimeout()).build(), json);
    }

    private SendResult send(MetaCloudWhatsAppGateway g, String to) {
        return g.sendTemplate(to, "fireflies_prenotazione_confermata", "it", List.of("Anna", "3/11/2026", "19:00", "4"), "k-1");
    }

    @Test
    void destinatarioAutorizzatoRichiestaConformeAllaCloudApi() {
        responder.set(e -> reply(e, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"390000000001\",\"wa_id\":\"390000000001\"}],"
                + "\"messages\":[{\"id\":\"wamid.TEST123\",\"message_status\":\"accepted\"}]}"));
        SendResult r = send(gateway(Duration.ofSeconds(5)), ALLOWED);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.ACCEPTED);
        assertThat(r.providerMessageId()).isEqualTo("wamid.TEST123");
        assertThat(paths).containsExactly("/v21.0/1234567890/messages");
        assertThat(auth).containsExactly("Bearer " + FAKE_TOKEN);
        JsonNode body = json.readTree(bodies.getFirst());
        assertThat(body.path("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(body.path("to").asString()).isEqualTo("390000000001");
        assertThat(body.path("type").asString()).isEqualTo("template");
        assertThat(body.path("template").path("name").asString()).isEqualTo("fireflies_prenotazione_confermata");
        assertThat(body.path("template").path("language").path("code").asString()).isEqualTo("it");
        JsonNode params = body.path("template").path("components").path(0).path("parameters");
        assertThat(body.path("template").path("components").path(0).path("type").asString()).isEqualTo("body");
        assertThat(params.size()).isEqualTo(4);
        assertThat(params.path(0).path("text").asString()).isEqualTo("Anna");
        assertThat(params.path(3).path("text").asString()).isEqualTo("4");
    }

    @Test
    void destinatarioNonAutorizzatoBloccatoSenzaChiamate() {
        responder.set(e -> reply(e, 200, "{}"));
        SendResult r = send(gateway(Duration.ofSeconds(5)), OTHER);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.REJECTED_PERMANENT);
        assertThat(r.errorCode()).isEqualTo("RECIPIENT_NOT_ALLOWED");
        assertThat(bodies).as("nessuna richiesta a Meta").isEmpty();
        assertThat(send(gateway(Duration.ofSeconds(5)), "non-un-numero").errorCode()).isEqualTo("INVALID_RECIPIENT");
        assertThat(bodies).isEmpty();
    }

    @Test
    void secondoNumeroDiProvaConSpaziAmmesso() {
        responder.set(e -> reply(e, 200, "{\"messages\":[{\"id\":\"wamid.B\"}]}"));
        assertThat(send(gateway(Duration.ofSeconds(5)), "+390000000009").outcome()).isEqualTo(SendResult.Outcome.ACCEPTED);
    }

    @Test
    void accountBloccato131031RifiutoDefinitivo() {
        responder.set(e -> reply(e, 400, error(131031, null)));
        SendResult r = send(gateway(Duration.ofSeconds(5)), ALLOWED);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.REJECTED_PERMANENT);
        assertThat(r.errorCode()).isEqualTo("ACCOUNT_LOCKED:131031").doesNotContain("390000000001");
    }

    @Test
    void tokenScadutoENonValido() {
        responder.set(e -> reply(e, 401, error(190, 463)));
        assertThat(send(gateway(Duration.ofSeconds(5)), ALLOWED)).isEqualTo(
                new SendResult(SendResult.Outcome.REJECTED_PERMANENT, null, "TOKEN_EXPIRED:190"));
        responder.set(e -> reply(e, 401, error(190, null)));
        assertThat(send(gateway(Duration.ofSeconds(5)), ALLOWED).errorCode()).isEqualTo("AUTH_INVALID:190");
        responder.set(e -> reply(e, 401, "non json"));
        assertThat(send(gateway(Duration.ofSeconds(5)), ALLOWED).errorCode()).isEqualTo("AUTH_INVALID");
    }

    @Test
    void timeoutDellaRispostaEsitoIgnotoMaiRitentatoDaSolo() {
        responder.set(e -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            reply(e, 200, "{\"messages\":[{\"id\":\"wamid.TARDI\"}]}");
        });
        SendResult r = send(gateway(Duration.ofMillis(300)), ALLOWED);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.UNKNOWN);
        assertThat(r.errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void connessioneRifiutataErroreTemporaneo() {
        int port = server.getAddress().getPort();
        server.stop(0);
        WhatsAppProperties c = config(URI.create("http://127.0.0.1:" + port), Duration.ofSeconds(2));
        MetaCloudWhatsAppGateway g = new MetaCloudWhatsAppGateway(c, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), json);
        SendResult r = send(g, ALLOWED);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.TRANSIENT_ERROR);
        assertThat(r.errorCode()).isEqualTo("NETWORK_CONNECT");
        server = null;
    }

    @Test
    void classificazioneDegliErroriDiMeta() {
        MetaCloudWhatsAppGateway g = gateway(Duration.ofSeconds(5));
        assertThat(g.classify(400, error(132001, null)).errorCode()).isEqualTo("TEMPLATE_NOT_FOUND:132001");
        assertThat(g.classify(400, error(132000, null)).errorCode()).isEqualTo("TEMPLATE_PARAM_MISMATCH:132000");
        assertThat(g.classify(400, error(131030, null)).errorCode()).isEqualTo("RECIPIENT_NOT_ALLOWED:131030");
        assertThat(g.classify(400, error(100, null)).errorCode()).isEqualTo("INVALID_PARAMETER:100");
        assertThat(g.classify(400, error(130429, null))).isEqualTo(new SendResult(SendResult.Outcome.TRANSIENT_ERROR, null, "RATE_LIMITED:130429"));
        assertThat(g.classify(400, error(131056, null)).outcome()).isEqualTo(SendResult.Outcome.TRANSIENT_ERROR);
        assertThat(g.classify(429, "").errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(g.classify(503, error(131016, null)).outcome()).isEqualTo(SendResult.Outcome.TRANSIENT_ERROR);
        assertThat(g.classify(500, error(131000, null))).as("errore interno: potrebbe essere partito")
                .isEqualTo(new SendResult(SendResult.Outcome.UNKNOWN, null, "META_ERROR:131000"));
        assertThat(g.classify(502, "<html>").outcome()).isEqualTo(SendResult.Outcome.UNKNOWN);
        assertThat(g.classify(200, "{}").outcome()).as("2xx senza id").isEqualTo(SendResult.Outcome.UNKNOWN);
        assertThat(g.classify(400, error(999999, null))).isEqualTo(new SendResult(SendResult.Outcome.REJECTED_PERMANENT, null, "META_ERROR:999999"));
    }
}
