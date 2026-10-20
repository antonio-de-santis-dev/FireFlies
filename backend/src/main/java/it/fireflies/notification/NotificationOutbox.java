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
 * In V1 non esiste ancora il worker di invio (FASE 7): le email restano {@code PENDING} («preparate, non inviate») e
 * gli SMS vengono registrati come {@code SKIPPED} finché il canale è spento. Nessun messaggio parte da qui.
 * Regola privacy: nel payload mai le note libere del cliente.
 */
@Service
public class NotificationOutbox {

    public enum Channel { EMAIL, SMS }

    public enum Recipient { CUSTOMER, STAFF }

    public record Entry(UUID id, String templateCode, String channel, String recipientKind, String state, String skipReason,
            Instant createdAt, Instant sentAt) {
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
        String skip = null;
        if (recipient == null || recipient.isBlank()) {
            skip = "NO_RECIPIENT";
        } else if (channel == Channel.SMS && !smsEnabled()) {
            skip = "SMS_DISABLED";
        }
        jdbc.sql("""
                INSERT INTO notification_outbox (business_event_key, aggregate_type, aggregate_id, channel, recipient_kind,
                                                 recipient, template_code, payload, state, skip_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (business_event_key) DO NOTHING
                """)
                .params(businessKey, aggregateType, aggregateId, channel.name(), recipientKind.name(),
                        recipient == null || recipient.isBlank() ? null : recipient, templateCode,
                        json.writeValueAsString(payload), skip == null ? "PENDING" : "SKIPPED", skip)
                .update();
    }

    /** Messaggi non ancora partiti resi inutili da un fatto successivo (es. conferma seguita da annullamento). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int supersede(String aggregateType, UUID aggregateId, List<String> templateCodes) {
        return jdbc.sql("""
                UPDATE notification_outbox SET state = 'SKIPPED', skip_reason = 'SUPERSEDED'
                 WHERE aggregate_type = ? AND aggregate_id = ? AND state = 'PENDING' AND template_code = ANY (?)
                """)
                .params(aggregateType, aggregateId, templateCodes.toArray(String[]::new)).update();
    }

    public List<Entry> forAggregate(String aggregateType, UUID aggregateId) {
        return jdbc.sql("""
                SELECT id, template_code, channel, recipient_kind, state, skip_reason, created_at, sent_at
                  FROM notification_outbox WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY created_at, id
                """)
                .params(aggregateType, aggregateId)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class).toInstant(),
                        rs.getObject(8, OffsetDateTime.class) == null ? null : rs.getObject(8, OffsetDateTime.class).toInstant()))
                .list();
    }

    private boolean smsEnabled() {
        return jdbc.sql("SELECT sms_enabled FROM site_settings WHERE id = 1").query(Boolean.class).optional().orElse(false);
    }
}
