package it.fireflies.notification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pannello di diagnostica WhatsApp del titolare, in sola lettura: modalità, cosa è configurato (solo sì/no), versione
 * dell'API, modello e lingua, quanti numeri di prova, conteggi per stato e ultimo codice d'errore. Mai token, segreti,
 * Phone Number ID, WABA ID o numeri di telefono; nessuna azione che possa far partire un messaggio.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WhatsAppStatusController {

    public record Configured(boolean accessToken, boolean phoneNumberId, boolean wabaId, boolean webhookVerifyToken,
            boolean appSecret) {
    }

    public record LastError(String code, Instant at) {
    }

    public record Status(String mode, boolean realSendsPossible, boolean webhookEnabled, Configured configured,
            String graphApiVersion, String templateName, String templateLanguage, int testRecipients,
            Map<String, Integer> counts, LastError lastError) {
    }

    private final WhatsAppProperties config;
    private final JdbcClient jdbc;

    WhatsAppStatusController(WhatsAppProperties config, JdbcClient jdbc) {
        this.config = config;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/notifications/whatsapp/status")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<Status> status() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : new String[] {"PENDING", "SENT", "DELIVERED", "READ", "FAILED", "UNCERTAIN", "SKIPPED"}) {
            counts.put(k, 0);
        }
        // «letti» e «non consegnati» secondo il webhook; «in coda» = in attesa o da ritentare
        jdbc.sql("""
                SELECT CASE WHEN delivery_status = 'READ' THEN 'READ'
                            WHEN delivery_status = 'FAILED' OR state = 'DEAD' THEN 'FAILED'
                            WHEN state IN ('PENDING', 'FAILED', 'SENDING') THEN 'PENDING'
                            ELSE state END, count(*)
                  FROM notification_outbox WHERE channel = 'WHATSAPP' GROUP BY 1
                """).query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .forEach(e -> counts.merge(e.getKey(), e.getValue(), Integer::sum));
        LastError last = jdbc.sql("""
                SELECT coalesce(o.delivery_error_code, o.last_error_code), coalesce(o.delivery_updated_at, o.created_at)
                  FROM notification_outbox o
                 WHERE o.channel = 'WHATSAPP' AND coalesce(o.delivery_error_code, o.last_error_code) IS NOT NULL
                 ORDER BY 2 DESC LIMIT 1
                """).query((rs, n) -> new LastError(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .optional().orElse(null);
        Status body = new Status(config.mode().name(), config.mode() == WhatsAppProperties.Mode.META_TEST, config.webhookEnabled(),
                new Configured(config.hasAccessToken(), config.hasPhoneNumberId(), config.hasWabaId(),
                        config.hasWebhookVerifyToken(), config.hasAppSecret()),
                config.graphApiVersion(), config.templateName(), config.templateLanguage(), config.allowedRecipients().size(),
                counts, last);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
