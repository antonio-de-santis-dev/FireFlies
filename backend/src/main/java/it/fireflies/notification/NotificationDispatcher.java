package it.fireflies.notification;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Invio dall'outbox (FASE 7, req. 11; ADR-0005), semantica at-least-once con dedupe:
 * <ul>
 * <li>presa in carico con lease ({@code FOR UPDATE SKIP LOCKED}): più worker non prendono lo stesso messaggio;</li>
 * <li>un tentativo per volta registrato in {@code notification_attempts} con la chiave di idempotenza del provider,
 * stabile tra i ritentativi automatici: un provider che la rispetta non manda due volte lo stesso messaggio;</li>
 * <li>esito: ACCEPTED → SENT; errore temporaneo → FAILED con attesa crescente, poi DEAD dopo {@code maxAttempts};
 * rifiuto definitivo → DEAD; esito ignoto (timeout dopo l'invio) → UNCERTAIN, mai ritentato da solo;
 * lease scaduto (worker morto a metà) → UNCERTAIN; scaduto o con modello spento → SKIPPED con il motivo;</li>
 * <li>l'esito lo scrive solo chi ha ancora il lease: un worker in ritardo non sovrascrive nulla.</li>
 * </ul>
 * Gateway email e SMS solo finti ({@link NotificationGatewaysConfiguration}). WhatsApp (V14) segue la modalità
 * {@code FIREFLIES_WHATSAPP_MODE} ({@link WhatsAppConfiguration}): DISABLED → SKIPPED senza chiamate, FAKE → simulato,
 * META_TEST → Cloud API di Meta solo verso i numeri di prova. Prima di ogni invio WhatsApp si ricontrollano consenso e
 * stato della prenotazione; l'id del messaggio di Meta resta nell'outbox per il webhook di consegna.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4));
    public static final int MAX_MANUAL_RESENDS = 3;

    public record Claimed(UUID id, String channel, String recipient, String templateCode, String payload, String idempotencyKey,
            int attempt, UUID lease) {
    }

    /** Esito di un giro del worker: quanti messaggi per stato finale. */
    public record Round(int recovered, int expired, Map<String, Integer> outcomes) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EmailGateway email;
    private final SmsGateway sms;
    private final WhatsAppGateway whatsApp;
    private final WhatsAppProperties whatsAppConfig;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration lease;

    NotificationDispatcher(JdbcClient jdbc, TransactionTemplate tx, EmailGateway email, SmsGateway sms, WhatsAppGateway whatsApp,
            WhatsAppProperties whatsAppConfig, ObjectMapper json, AuditLog audit,
            @Value("${fireflies.notifications.worker.batch-size:20}") int batchSize,
            @Value("${fireflies.notifications.worker.max-attempts:5}") int maxAttempts,
            @Value("${fireflies.notifications.worker.lease:PT2M}") Duration lease) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.email = email;
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.whatsAppConfig = whatsAppConfig;
        this.json = json;
        this.audit = audit;
        this.batchSize = batchSize;
        this.maxAttempts = Math.min(maxAttempts, 10); // ck_outbox_attempts
        this.lease = lease;
    }

    /** Un giro: lease scaduti, messaggi scaduti, poi un lotto di invii. */
    public Round runOnce() {
        int recovered = recoverExpiredLeases();
        int expired = expireStale();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        for (Claimed c : claim()) {
            outcomes.merge(process(c), 1, Integer::sum);
        }
        return new Round(recovered, expired, outcomes);
    }

    /** Lavoratore sparito durante l'invio: non sappiamo se il messaggio è partito → UNCERTAIN (da verificare a mano). */
    int recoverExpiredLeases() {
        Integer n = tx.execute(s -> jdbc.sql("""
                UPDATE notification_outbox
                   SET state = 'UNCERTAIN', last_error_code = 'LEASE_EXPIRED', lease_token = NULL, locked_until = NULL
                 WHERE state = 'SENDING' AND locked_until < now()
                """).update());
        return n == null ? 0 : n;
    }

    /** Messaggi non più utili (la prenotazione è passata): non si mandano più, restano nello storico come saltati. */
    public int expireStale() {
        Integer n = tx.execute(s -> jdbc.sql("""
                UPDATE notification_outbox SET state = 'SKIPPED', skip_reason = 'EXPIRED'
                 WHERE state IN ('PENDING', 'FAILED')
                   AND (expires_at < now() OR (expires_at IS NULL AND created_at < now() - interval '30 days'))
                """).update());
        return n == null ? 0 : n;
    }

    List<Claimed> claim() {
        UUID token = UUID.randomUUID();
        List<Claimed> claimed = tx.execute(s -> jdbc.sql("""
                UPDATE notification_outbox
                   SET state = 'SENDING', lease_token = ?, locked_until = now() + make_interval(secs => ?), attempts = attempts + 1
                 WHERE id IN (SELECT id FROM notification_outbox
                               WHERE state IN ('PENDING', 'FAILED') AND next_attempt_at <= now() AND attempts < ?
                               ORDER BY next_attempt_at, id
                               LIMIT ?
                                 FOR UPDATE SKIP LOCKED)
                RETURNING id, channel, recipient, template_code, payload::text, provider_idempotency_key, attempts, lease_token
                """).params(token, (double) lease.toSeconds(), maxAttempts, batchSize)
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getObject(8, UUID.class)))
                .list());
        return claimed == null ? List.of() : claimed;
    }

    /** Invia un messaggio preso in carico e ne registra l'esito. Restituisce lo stato finale. */
    String process(Claimed c) {
        if (c.channel().equals("WHATSAPP")) {
            return processWhatsApp(c);
        }
        Optional<String[]> template = jdbc.sql("SELECT subject, body FROM notification_templates WHERE code = ? AND channel = ? AND active")
                .params(c.templateCode(), c.channel()).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
        if (template.isEmpty()) {
            finish(c, "SKIPPED", "TEMPLATE_INACTIVE", null, null);
            return "SKIPPED";
        }
        Map<String, String> values = values(c.payload());
        String subject = template.get()[0] == null ? null : render(template.get()[0], values);
        String body = render(template.get()[1], values);
        String provider = c.channel().equals("SMS") ? sms.providerName() : email.providerName();
        long attemptId = startAttempt(c, provider);
        long started = System.nanoTime();
        SendResult result;
        try {
            result = c.channel().equals("SMS") ? sms.send(c.recipient(), body, c.idempotencyKey())
                    : email.send(c.recipient(), subject, body, c.idempotencyKey());
        } catch (RuntimeException e) {
            // un'eccezione può arrivare dopo che il provider ha già accettato: niente ritentativo cieco
            log.warn("Invio {} {}: eccezione del gateway ({})", c.channel(), c.id(), e.getClass().getSimpleName());
            result = new SendResult(SendResult.Outcome.UNKNOWN, null, "GATEWAY_EXCEPTION");
        }
        SendResult outcome = result;
        endAttempt(attemptId, outcome, started);
        return settle(c, outcome);
    }

    /**
     * WhatsApp: modalità spenta → SKIPPED senza chiamate; consenso revocato o prenotazione non più confermata → SKIPPED;
     * parametri del modello non validi → DEAD senza chiamate; poi un tentativo come per gli altri canali.
     */
    private String processWhatsApp(Claimed c) {
        if (whatsApp.mode() == WhatsAppProperties.Mode.DISABLED) {
            return finish(c, "SKIPPED", "WHATSAPP_DISABLED", null, null);
        }
        String blocked = jdbc.sql("""
                SELECT CASE WHEN r.id IS NULL OR r.status <> 'CONFIRMED' THEN 'SUPERSEDED'
                            WHEN r.whatsapp_consent_at IS NULL OR r.whatsapp_consent_withdrawn_at IS NOT NULL THEN 'NO_CONSENT'
                            ELSE '' END
                  FROM notification_outbox o LEFT JOIN reservations r ON r.id = o.aggregate_id WHERE o.id = ?
                """).param(c.id()).query(String.class).optional().orElse("SUPERSEDED");
        if (!blocked.isEmpty()) {
            return finish(c, "SKIPPED", blocked, null, null);
        }
        Optional<List<String>> parameters = confirmationParameters(values(c.payload()));
        if (parameters.isEmpty()) {
            return finish(c, "DEAD", null, "INVALID_PARAMETER", null);
        }
        long attemptId = startAttempt(c, whatsApp.providerName());
        long started = System.nanoTime();
        SendResult result;
        try {
            result = whatsApp.sendTemplate(c.recipient(), whatsAppConfig.templateName(), whatsAppConfig.templateLanguage(),
                    parameters.get(), c.idempotencyKey());
        } catch (RuntimeException e) {
            log.warn("Invio WHATSAPP {}: eccezione del gateway ({})", c.id(), e.getClass().getSimpleName());
            result = new SendResult(SendResult.Outcome.UNKNOWN, null, "GATEWAY_EXCEPTION");
        }
        endAttempt(attemptId, result, started);
        if (result.outcome() == SendResult.Outcome.ACCEPTED && result.providerMessageId() != null) {
            jdbc.sql("UPDATE notification_outbox SET provider_message_id = ? WHERE id = ? AND lease_token = ?")
                    .params(result.providerMessageId(), c.id(), c.lease()).update();
        }
        return settle(c, result);
    }

    /**
     * Parametri del modello {@code fireflies_prenotazione_confermata}: {{1}} nome, {{2}} data, {{3}} ora, {{4}} persone.
     * Meta rifiuta parametri vuoti, con a capo o tabulazioni, o con più di quattro spazi di fila: si normalizzano gli
     * spazi e un valore vuoto o troppo lungo blocca l'invio prima di qualunque chiamata.
     */
    static Optional<List<String>> confirmationParameters(Map<String, String> values) {
        List<String> out = new java.util.ArrayList<>();
        for (String key : List.of("nome", "data", "ora", "persone")) {
            String v = values.getOrDefault(key, "").replaceAll("\\s+", " ").strip();
            if (v.isEmpty() || v.length() > 200) {
                return Optional.empty();
            }
            out.add(v);
        }
        return Optional.of(List.copyOf(out));
    }

    private long startAttempt(Claimed c, String provider) {
        return jdbc.sql("""
                INSERT INTO notification_attempts (outbox_id, attempt_no, provider, idempotency_key_used)
                VALUES (?, (SELECT coalesce(max(attempt_no), 0) + 1 FROM notification_attempts WHERE outbox_id = ?), ?, ?) RETURNING id
                """).params(c.id(), c.id(), provider, c.idempotencyKey()).query(Long.class).single();
    }

    private void endAttempt(long attemptId, SendResult outcome, long started) {
        jdbc.sql("""
                UPDATE notification_attempts SET finished_at = now(), outcome = ?, provider_message_id = ?, error_code = ?, duration_ms = ?
                 WHERE id = ?
                """).params(outcome.outcome().name(), outcome.providerMessageId(), outcome.errorCode(),
                (int) Duration.ofNanos(System.nanoTime() - started).toMillis(), attemptId).update();
    }

    private String settle(Claimed c, SendResult outcome) {
        return switch (outcome.outcome()) {
            case ACCEPTED -> finish(c, "SENT", null, null, null);
            case REJECTED_PERMANENT -> finish(c, "DEAD", null, outcome.errorCode(), null);
            case UNKNOWN -> finish(c, "UNCERTAIN", null, outcome.errorCode(), null);
            case TRANSIENT_ERROR -> c.attempt() >= maxAttempts ? finish(c, "DEAD", null, outcome.errorCode(), null)
                    : finish(c, "FAILED", null, outcome.errorCode(), BACKOFF.get(Math.min(c.attempt(), BACKOFF.size()) - 1));
        };
    }

    /** Esito scritto solo se il lease è ancora nostro; altrimenti lo stato resta a chi l'ha ripreso. */
    private String finish(Claimed c, String state, String skipReason, String errorCode, Duration retryIn) {
        int updated = jdbc.sql("""
                UPDATE notification_outbox
                   SET state = ?, skip_reason = ?, last_error_code = coalesce(?, last_error_code), lease_token = NULL, locked_until = NULL,
                       sent_at = CASE WHEN ? = 'SENT' THEN now() ELSE sent_at END,
                       purge_after = CASE WHEN ? IN ('SENT', 'DEAD', 'SKIPPED') THEN now() + interval '90 days' ELSE purge_after END,
                       next_attempt_at = CASE WHEN ? > 0 THEN now() + make_interval(secs => ?) ELSE next_attempt_at END
                 WHERE id = ? AND lease_token = ? AND state = 'SENDING'
                """).params(state, skipReason, errorCode, state, state, retrySeconds(retryIn), retrySeconds(retryIn), c.id(), c.lease())
                .update();
        if (updated == 0) {
            log.warn("Notifica {}: lease perso, esito {} non registrato", c.id(), state);
            return "LEASE_LOST";
        }
        return state;
    }

    private static double retrySeconds(Duration retryIn) {
        return retryIn == null ? 0.0 : (double) retryIn.toSeconds();
    }

    private Map<String, String> values(String payload) {
        Map<String, String> out = new LinkedHashMap<>();
        if (payload == null) {
            return out;
        }
        JsonNode node = json.readTree(payload);
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asString()));
        // telefono del locale letto al momento dell'invio (il titolare può averlo aggiornato dopo l'accodamento)
        out.computeIfAbsent("telefono_locale", k -> jdbc.sql("SELECT coalesce(phone_public, '') FROM site_settings WHERE id = 1")
                .query(String.class).optional().orElse(""));
        return out;
    }

    /** {{chiave}} → valore del payload; un segnaposto senza valore diventa vuoto (mai il segnaposto grezzo al cliente). */
    static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Nuovo invio deciso da un gestore per un messaggio non riuscito o incerto (al massimo {@value #MAX_MANUAL_RESENDS}
     * volte). Per UNCERTAIN la chiave di idempotenza resta la stessa: se il primo invio era partito, il provider non
     * lo ripete. Registrato nell'audit.
     */
    public void resend(UUID actor, UUID id) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> row = jdbc.sql("SELECT state, manual_resend_count, aggregate_id, channel FROM notification_outbox WHERE id = ? FOR UPDATE")
                    .param(id).query().listOfRows().stream().findFirst().orElseThrow(() -> ApiErrors.notFound("La notifica non esiste."));
            String state = (String) row.get("state");
            if ("WHATSAPP".equals(row.get("channel"))) {
                // Meta non ha chiavi di idempotenza: un nuovo invio manuale potrebbe arrivare due volte al cliente
                throw new ApiException(HttpStatus.CONFLICT, "WHATSAPP_RESEND_DISABLED", "Operazione non possibile",
                        "I messaggi WhatsApp non si inviano di nuovo dall'area gestori: se serve, contatta il cliente a voce.");
            }
            if (!List.of("FAILED", "DEAD", "UNCERTAIN").contains(state)) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Operazione non possibile",
                        "Si può inviare di nuovo solo una notifica non riuscita o dall'esito incerto.");
            }
            if (((Number) row.get("manual_resend_count")).intValue() >= MAX_MANUAL_RESENDS) {
                throw new ApiException(HttpStatus.CONFLICT, "RESEND_LIMIT", "Troppi nuovi invii",
                        "Questa notifica è già stata inviata di nuovo " + MAX_MANUAL_RESENDS + " volte: contatta il cliente a voce.");
            }
            jdbc.sql("""
                    UPDATE notification_outbox SET state = 'PENDING', next_attempt_at = now(), attempts = 0, last_error_code = NULL,
                           manual_resend_count = manual_resend_count + 1, purge_after = NULL WHERE id = ?
                    """).param(id).update();
            audit.record(actor, AuditAction.NOTIFICATION_RESENT, "NOTIFICATION", id.toString(),
                    Map.of("fromState", state, "aggregateId", String.valueOf(row.get("aggregate_id"))));
        });
    }
}
