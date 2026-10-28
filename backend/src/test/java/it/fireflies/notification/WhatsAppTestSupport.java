package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import it.fireflies.reservation.ReservationTestSupport;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;

/** Supporto comune dei test WhatsApp: richieste con o senza consenso, conferma, webhook firmato. Solo dati di prova. */
abstract class WhatsAppTestSupport extends ReservationTestSupport {

    /** Credenziali FINTE dei test: non sono e non somigliano a credenziali Meta reali. */
    static final String VERIFY_TOKEN = "verify-token-di-prova";
    static final String APP_SECRET = "app-secret-di-prova";
    static final String PHONE_NUMBER_ID = "1234567890";

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    WhatsAppGateway whatsApp;

    @Autowired
    SmsGateway smsGateway;

    @Autowired
    EmailGateway emailGateway;

    protected ApiClient owner;
    protected ApiClient staff;

    @BeforeEach
    void setUpWhatsApp() {
        owner = signedIn(Role.OWNER);
        staff = signedIn(Role.STAFF);
        configure(owner, 20);
        ((FakeSmsGateway) smsGateway).reset();
        ((FakeEmailGateway) emailGateway).reset();
        if (whatsApp instanceof FakeWhatsAppGateway fake) {
            fake.reset();
        }
    }

    /** Richiesta dal sito con la casella WhatsApp spuntata o no; restituisce l'id IN ATTESA. */
    protected UUID request(String time, boolean consent) {
        Map<String, Object> body = webBody(DAY, time, 2, nextPhone());
        body.put("whatsappConsent", consent);
        ApiClient.Response r = web(browser(), body);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return idOf(r.body().path("reference").asString());
    }

    protected List<Map<String, Object>> whatsAppRows(UUID reservation) {
        return jdbc.sql("""
                SELECT id, state, skip_reason, last_error_code, provider_message_id, delivery_status, recipient, attempts
                  FROM notification_outbox WHERE aggregate_id = ? AND channel = 'WHATSAPP'
                """).param(reservation).query().listOfRows();
    }

    protected Map<String, Object> whatsAppRow(UUID reservation) {
        List<Map<String, Object>> rows = whatsAppRows(reservation);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    protected UUID confirmed(boolean consent) {
        UUID id = request("19:00", consent);
        ApiClient.Response c = confirm(staff, id);
        assertThat(c.status()).as(c.raw()).isEqualTo(200);
        return id;
    }

    protected HttpResponse<String> webhook(String body, String signature) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + WhatsAppWebhookController.PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (signature != null) {
            b.header("X-Hub-Signature-256", signature);
        }
        try {
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> webhookGet(String query) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + WhatsAppWebhookController.PATH + "?" + query)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Corpo come quello di Meta con uno stato di consegna (numero del destinatario finto). */
    static String statusEvent(String messageId, String status, long timestamp, String extra) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"0","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp","metadata":{"display_phone_number":"000","phone_number_id":"%s"},
                  "statuses":[{"id":"%s","status":"%s","timestamp":"%d","recipient_id":"390000000000"%s}]%s}}]}]}
                """.formatted(PHONE_NUMBER_ID, messageId, status, timestamp,
                status.equals("failed") ? ",\"errors\":[{\"code\":131026,\"title\":\"Message undeliverable\"}]" : "", extra);
    }
}
