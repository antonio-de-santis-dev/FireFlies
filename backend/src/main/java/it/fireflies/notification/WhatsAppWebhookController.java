package it.fireflies.notification;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/webhooks/whatsapp}: chiamato da Meta, quindi senza login dei gestori e senza CSRF, ma protetto da
 * verify token (GET), firma HMAC del corpo grezzo (POST), limite di dimensione del corpo e limite di richieste per IP.
 * Senza verify token e App Secret configurati risponde 404 (predefinito). Il server locale non viene mai esposto a
 * Internet da qui: per provarlo con Meta serve un tunnel avviato a mano (WHATSAPP_LOCAL_TEST_UBUNTU.md).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WhatsAppWebhookController {

    static final String PATH = "/api/v1/webhooks/whatsapp";
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final WhatsAppWebhookService webhook;
    private final Clock clock;
    private final int perMinute;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    WhatsAppWebhookController(WhatsAppWebhookService webhook, Clock clock,
            @Value("${fireflies.whatsapp.webhook-requests-per-minute:300}") int perMinute) {
        this.webhook = webhook;
        this.clock = clock;
        this.perMinute = perMinute;
    }

    @GetMapping(PATH)
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge, HttpServletRequest request) {
        if (!webhook.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (limited(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return webhook.verify(mode, token, challenge)
                .map(c -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(c))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    @PostMapping(PATH)
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            HttpServletRequest request) throws IOException {
        if (!webhook.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (limited(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!webhook.signatureValid(body, signature)) {
            log.warn("Webhook WhatsApp: firma assente o non valida, richiesta rifiutata");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        webhook.handle(body);
        return ResponseEntity.ok().build(); // 200 sempre con firma valida: Meta non ripete gli eventi già consegnati
    }

    /** Finestra fissa di un minuto per IP, in memoria (una sola istanza del backend, come gli altri limitatori). */
    private boolean limited(HttpServletRequest request) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue()[0] != minute);
        }
        long[] w = windows.compute(request.getRemoteAddr(), (ip, old) -> old == null || old[0] != minute
                ? new long[] {minute, 1} : new long[] {minute, old[1] + 1});
        return w[1] > perMinute;
    }
}
