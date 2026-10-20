package it.fireflies.notification;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Accodamento delle notifiche nell'outbox (ADR-0005), nella stessa transazione del fatto che le genera.
 * L'invio lo fa {@link NotificationDispatcher} (FASE 7), solo con gateway finti: senza worker acceso le email restano
 * {@code PENDING} («preparate, non inviate»); gli SMS sono {@code SKIPPED} finché il canale è spento.
 * Regola privacy: nel payload mai le note libere del cliente.
 */
@Service
public class NotificationOutbox {

    public enum Channel { EMAIL, SMS }

    public enum Recipient { CUSTOMER, STAFF }

    /** {@code provider}: chi ha gestito l'ultimo tentativo ({@code fake} = invio simulato, nessun messaggio reale). */
    public record Entry(UUID id, String templateCode, String channel, String recipientKind, String state, String skipReason,
            Instant createdAt, Instant sentAt, String provider, int manualResends) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    NotificationOutbox(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Accoda un messaggio. La chiave di business è unica: accodare due volte lo stesso fatto non crea un doppione.
     * Senza destinatario, o per SMS con il canale spento, il messaggio è registrato come saltato con il motivo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String businessKey, String aggregateType, UUID aggregateId, Channel channel, Recipient recipientKind,
            String recipient, String templateCode, Map<String, ?> payload) {
        enqueue(businessKey, aggregateType, aggregateId, channel, recipientKind, recipient, templateCode, payload, null);
    }

    /** Come sopra, con la scadenza: dopo {@code expiresAt} il messaggio non si manda più (SKIPPED, motivo EXPIRED). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String businessKey, String aggregateType, UUID aggregateId, Channel channel, Recipient recipientKind,
            String recipient, String templateCode, Map<String, ?> payload, Instant expiresAt) {
        String skip = null;
        if (recipient == null || recipient.isBlank()) {
            skip = "NO_RECIPIENT";
        } else if (channel == Channel.SMS && !smsEnabled()) {
            skip = "SMS_DISABLED";
        }
        jdbc.sql("""
                INSERT INTO notification_outbox (business_event_key, aggregate_type, aggregate_id, channel, recipient_kind,
                                                 recipient, template_code, payload, state, skip_reason, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (business_event_key) DO NOTHING
                """)
                .params(businessKey, aggregateType, aggregateId, channel.name(), recipientKind.name(),
                        recipient == null || recipient.isBlank() ? null : recipient, templateCode,
                        json.writeValueAsString(payload), skip == null ? "PENDING" : "SKIPPED", skip,
                        expiresAt == null ? null : expiresAt.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    /** Messaggi non ancora partiti resi inutili da un fatto successivo (es. conferma seguita da annullamento). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int supersede(String aggregateType, UUID aggregateId, List<String> templateCodes) {
        return jdbc.sql("""
                UPDATE notification_outbox SET state = 'SKIPPED', skip_reason = 'SUPERSEDED'
                 WHERE aggregate_type = ? AND aggregate_id = ? AND state IN ('PENDING', 'FAILED') AND template_code = ANY (?)
                """)
                .params(aggregateType, aggregateId, templateCodes.toArray(String[]::new)).update();
    }

    public List<Entry> forAggregate(String aggregateType, UUID aggregateId) {
        return jdbc.sql("""
                SELECT o.id, o.template_code, o.channel, o.recipient_kind, o.state, o.skip_reason, o.created_at, o.sent_at,
                       (SELECT a.provider FROM notification_attempts a WHERE a.outbox_id = o.id ORDER BY a.attempt_no DESC LIMIT 1),
                       o.manual_resend_count
                  FROM notification_outbox o WHERE o.aggregate_type = ? AND o.aggregate_id = ? ORDER BY o.created_at, o.id
                """)
                .params(aggregateType, aggregateId)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class).toInstant(),
                        rs.getObject(8, OffsetDateTime.class) == null ? null : rs.getObject(8, OffsetDateTime.class).toInstant(),
                        rs.getString(9), rs.getInt(10)))
                .list();
    }

    private boolean smsEnabled() {
        return jdbc.sql("SELECT sms_enabled FROM site_settings WHERE id = 1").query(Boolean.class).optional().orElse(false);
    }
}
