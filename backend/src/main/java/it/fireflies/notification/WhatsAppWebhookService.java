package it.fireflies.notification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook della WhatsApp Cloud API: verifica dell'iscrizione (GET con il verify token) e stati di consegna (POST firmato
 * {@code X-Hub-Signature-256} = HMAC-SHA256 del corpo grezzo con l'App Secret).
 * <ul>
 * <li>solo gli oggetti {@code statuses} (sent, delivered, read, failed): i messaggi in arrivo dai clienti non si leggono,
 * non si salvano e non ricevono risposte (nessun chatbot);</li>
 * <li>correlazione con l'id del messaggio di Meta salvato nell'outbox all'invio;</li>
 * <li>deduplica per (id, stato, ora) in {@code whatsapp_webhook_events}, solo chiavi tecniche;</li>
 * <li>stati fuori ordine: si avanza solo (sent → delivered → read); {@code failed} vale solo prima della consegna;
 * nessuna regressione;</li>
 * <li>un errore di consegna cambia solo la notifica, mai la prenotazione, che resta confermata.</li>
 * </ul>
 */
@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);
    private static final Map<String, Integer> RANK = Map.of("SENT", 1, "DELIVERED", 2, "READ", 3);

    /** Esito dell'elaborazione di un POST con firma valida (per i log e i test). */
    public record Result(int applied, int duplicates, int ignored) {
    }

    private final WhatsAppProperties config;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    WhatsAppWebhookService(WhatsAppProperties config, JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.config = config;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    public boolean enabled() {
        return config.webhookEnabled();
    }

    /** GET di verifica: restituisce la challenge solo con mode=subscribe e il verify token giusto. */
    public Optional<String> verify(String mode, String token, String challenge) {
        if (!enabled() || !"subscribe".equals(mode) || token == null || challenge == null || challenge.length() > 200
                || !challenge.matches("[A-Za-z0-9_\\-.]+")) {
            return Optional.empty();
        }
        boolean same = MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                config.webhookVerifyToken().getBytes(StandardCharsets.UTF_8));
        return same ? Optional.of(challenge) : Optional.empty();
    }

    /** Firma {@code sha256=<hex>} calcolata sul corpo grezzo, confronto a tempo costante. */
    public boolean signatureValid(byte[] rawBody, String header) {
        if (!enabled() || header == null || !header.startsWith("sha256=") || header.length() != 7 + 64) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 non disponibile", e);
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(7).toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, given);
    }

    /** Elabora un corpo con firma già verificata. Un corpo illeggibile non è un errore per Meta: si ignora. */
    public Result handle(byte[] rawBody) {
        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (RuntimeException e) {
            log.warn("Webhook WhatsApp: corpo non leggibile, ignorato");
            return new Result(0, 0, 1);
        }
        int applied = 0;
        int duplicates = 0;
        int ignored = 0;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                String phoneId = value.path("metadata").path("phone_number_id").asString("");
                if (config.hasPhoneNumberId() && !phoneId.isEmpty() && !phoneId.equals(config.phoneNumberId())) {
                    ignored++; // un altro numero dello stesso account: non è nostro
                    continue;
                }
                // value.messages (messaggi dei clienti) volutamente ignorato: nessun contenuto salvato, nessuna risposta
                for (JsonNode status : value.path("statuses")) {
                    switch (apply(status)) {
                        case "APPLIED" -> applied++;
                        case "DUPLICATE" -> duplicates++;
                        default -> ignored++;
                    }
                }
            }
        }
        jdbc.sql("DELETE FROM whatsapp_webhook_events WHERE received_at < now() - interval '30 days'").update();
        if (applied + duplicates + ignored > 0) {
            log.info("Webhook WhatsApp: {} stati applicati, {} doppioni, {} ignorati", applied, duplicates, ignored);
        }
        return new Result(applied, duplicates, ignored);
    }

    private String apply(JsonNode status) {
        String messageId = status.path("id").asString("");
        String newStatus = status.path("status").asString("").toUpperCase(java.util.Locale.ROOT);
        String timestamp = status.path("timestamp").asString("");
        if (messageId.isEmpty() || messageId.length() > 120 || !(RANK.containsKey(newStatus) || "FAILED".equals(newStatus))
                || !timestamp.matches("\\d{1,12}")) {
            return "IGNORED";
        }
        String errorCode = "FAILED".equals(newStatus) ? failureCode(status) : null;
        Instant at = Instant.ofEpochSecond(Long.parseLong(timestamp));
        String outcome = tx.execute(s -> {
            int fresh = jdbc.sql("INSERT INTO whatsapp_webhook_events (event_key) VALUES (?) ON CONFLICT DO NOTHING")
                    .param(messageId + "|" + newStatus + "|" + timestamp).update();
            if (fresh == 0) {
                return "DUPLICATE";
            }
            Optional<String> current = jdbc.sql("""
                    SELECT coalesce(delivery_status, '') FROM notification_outbox
                     WHERE provider_message_id = ? AND channel = 'WHATSAPP' FOR UPDATE
                    """).param(messageId).query(String.class).optional();
            if (current.isEmpty()) {
                return "IGNORED"; // messaggio sconosciuto (non nostro, o già eliminato dalla conservazione)
            }
            if (!advances(current.get(), newStatus)) {
                return "IGNORED"; // fuori ordine o regressione: lo stato resta quello più avanzato
            }
            jdbc.sql("""
                    UPDATE notification_outbox
                       SET delivery_status = ?, delivery_updated_at = ?, delivery_error_code = ?,
                           state = CASE WHEN ? IN ('DELIVERED', 'READ') AND state IN ('SENT', 'UNCERTAIN') THEN 'DELIVERED'
                                        WHEN ? = 'FAILED' AND state IN ('SENT', 'UNCERTAIN') THEN 'DEAD'
                                        WHEN ? = 'SENT' AND state = 'UNCERTAIN' THEN 'SENT'
                                        ELSE state END,
                           last_error_code = CASE WHEN ? = 'FAILED' THEN ? ELSE last_error_code END,
                           purge_after = CASE WHEN purge_after IS NULL AND ? <> 'SENT' THEN now() + interval '90 days' ELSE purge_after END
                     WHERE provider_message_id = ? AND channel = 'WHATSAPP'
                    """).params(newStatus, at.atOffset(ZoneOffset.UTC), errorCode, newStatus, newStatus, newStatus, newStatus,
                    errorCode, newStatus, messageId).update();
            return "APPLIED";
        });
        return outcome == null ? "IGNORED" : outcome;
    }

    /** Si avanza solo: sent → delivered → read; failed solo se non ancora consegnato; dopo failed nulla cambia. */
    static boolean advances(String current, String next) {
        if (current.equals("FAILED")) {
            return false;
        }
        if (next.equals("FAILED")) {
            return current.isEmpty() || current.equals("SENT");
        }
        return RANK.get(next) > RANK.getOrDefault(current, 0);
    }

    /** Solo il codice numerico dell'errore di Meta (il titolo e i dettagli possono contenere dati personali). */
    private static String failureCode(JsonNode status) {
        JsonNode code = status.path("errors").path(0).path("code");
        return code.isNumber() ? "META_" + code.asInt() : "META_FAILED";
    }
}
