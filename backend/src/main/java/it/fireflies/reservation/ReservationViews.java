package it.fireflies.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.common.BusinessTime;
import it.fireflies.notification.NotificationOutbox;
import tools.jackson.databind.ObjectMapper;

/**
 * Viste di lettura delle prenotazioni per l'area gestori: elenco, dettaglio con storico, modifiche (audit), tavoli,
 * notifiche preparate e situazione dei posti nell'intervallo. Solo letture: nessun lock, nessun effetto.
 */
@Component
public class ReservationViews {

    public record Summary(UUID id, String reference, String status, String statusReason, String source, LocalDate date,
            LocalTime time, LocalTime endTime, int guests, String customerName, String customerPhone, String customerEmail,
            String notes, boolean needsCallback, UUID zoneId, List<String> tables, Instant createdAt, int version) {
    }

    public record HistoryEntry(String fromStatus, String toStatus, String reason, String note, String by, Instant at) {
    }

    public record ChangeEntry(String action, String by, Instant at, Map<String, Object> details) {
    }

    public record TableEntry(UUID tableId, String code, String area, int maxSeats, boolean seatsOverride,
            String overrideReason) {
    }

    /**
     * Notifica accodata. {@code delivered} è vero solo se un invio reale è avvenuto ({@code SENT}): in V1 non
     * succede mai, le email restano «preparate, non inviate» e gli SMS «non previsti» (canale spento).
     */
    public record NotificationEntry(String template, String channel, String recipient, String state, String skipReason,
            boolean delivered, Instant createdAt) {
    }

    /** Posti nell'intervallo della prenotazione: minimo libero tra i quanti, e persone di altre richieste in attesa. */
    public record CapacityInfo(UUID zoneId, int capacity, int minFree, int pendingOthers, boolean eventBlocks,
            boolean fits) {
    }

    public record Detail(Summary summary, String notes, String internalNote, String zoneName, int durationMinutes,
            String createdBy, String handledBy, Instant confirmedAt, Instant closedAt, Instant updatedAt,
            List<TableEntry> tables, List<HistoryEntry> history, List<ChangeEntry> changes,
            List<NotificationEntry> notifications, CapacityInfo capacity, List<String> actions, List<String> warnings) {
    }

    private final ReservationStore store;
    private final CapacityLedger ledger;
    private final NotificationOutbox outbox;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    ReservationViews(ReservationStore store, CapacityLedger ledger, NotificationOutbox outbox, JdbcClient jdbc, ObjectMapper json,
            Clock clock) {
        this.clock = clock;
        this.store = store;
        this.ledger = ledger;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Summary summary(ReservationStore.Row r) {
        ZonedDateTime start = r.startsAt().atZone(BusinessTime.ZONE);
        List<String> tables = store.assignments(r.id(), true).stream().map(ReservationStore.AssignmentRow::tableCode).toList();
        return new Summary(r.id(), r.publicRef(), r.status(), r.statusReason(), r.source(), start.toLocalDate(),
                start.toLocalTime(), r.endsAt().atZone(BusinessTime.ZONE).toLocalTime(), r.guestCount(), r.customerName(),
                r.customerPhone(), r.customerEmail(), r.customerNotes(), r.needsCallback(), r.zoneId(), tables,
                r.createdAt(), r.version());
    }

    Detail detail(ReservationStore.Row r, List<String> warnings) {
        List<TableEntry> tables = store.assignments(r.id(), true).stream()
                .map(a -> new TableEntry(a.tableId(), a.tableCode(), a.areaName(), a.maxSeats(), a.seatsOverride(), a.overrideReason()))
                .toList();
        List<HistoryEntry> history = store.historyOf(r.id()).stream()
                .map(h -> new HistoryEntry(h.fromStatus(), h.toStatus(), h.reason(), h.note(),
                        h.changedBy() == null ? null : h.changedByName(), h.changedAt()))
                .toList();
        List<NotificationEntry> notifications = outbox.forAggregate(ReservationService.AGGREGATE, r.id()).stream()
                .map(n -> new NotificationEntry(n.templateCode(), n.channel(), n.recipientKind(), n.state(), n.skipReason(),
                        "SENT".equals(n.state()), n.createdAt()))
                .toList();
        return new Detail(summary(r), r.customerNotes(), r.internalNote(), zoneName(r.zoneId()),
                (int) Duration.between(r.startsAt(), r.endsAt()).toMinutes(), userName(r.createdBy()), userName(r.handledBy()),
                r.confirmedAt(), r.closedAt(), r.updatedAt(), tables, history, changes(r.id()), notifications, capacity(r),
                actions(r, Instant.now(clock)), warnings);
    }

    /** Email pubblica del locale (Impostazioni del sito): destinataria degli avvisi «nuova richiesta». */
    String venueEmail() {
        return jdbc.sql("SELECT email_public FROM site_settings WHERE id = 1").query(String.class).optional().orElse(null);
    }

    private CapacityInfo capacity(ReservationStore.Row r) {
        if (r.zoneId() == null || !(r.status().equals("PENDING") || r.status().equals("CONFIRMED"))) {
            return null;
        }
        List<CapacityLedger.QuantumState> quanta = ledger.read(r.zoneId(), r.startsAt(), r.endsAt());
        int capacity = quanta.stream().mapToInt(CapacityLedger.QuantumState::capacityCovers).min().orElse(0);
        int minFree = quanta.stream().mapToInt(CapacityLedger.QuantumState::free).min().orElse(0);
        boolean event = quanta.stream().anyMatch(q -> q.exclusiveEventId() != null);
        int pending = store.pendingCovers(r.zoneId(), r.startsAt(), r.endsAt());
        if (r.status().equals("PENDING")) {
            pending -= r.guestCount();
        }
        // per una confermata i suoi posti sono già nel registro: «ci sta» per definizione
        boolean fits = r.status().equals("CONFIRMED") || (!event && minFree >= r.guestCount());
        return new CapacityInfo(r.zoneId(), capacity, minFree, Math.max(0, pending), event, fits);
    }

    /** Azioni possibili adesso (stesse regole di {@link ReservationService}); CORRECT è riservata al titolare. */
    static List<String> actions(ReservationStore.Row r, Instant now) {
        List<String> actions = new ArrayList<>();
        switch (r.status()) {
            case "PENDING" -> {
                if (r.startsAt().isAfter(now)) {
                    actions.add("CONFIRM");
                }
                actions.add("REJECT");
                actions.add("CANCEL");
                actions.add("EDIT");
            }
            case "CONFIRMED" -> {
                if (now.isBefore(r.startsAt())) {
                    actions.add("CANCEL");
                } else {
                    actions.add("COMPLETE");
                }
                if (!now.isBefore(r.startsAt().plus(Duration.ofMinutes(15)))) {
                    actions.add("NO_SHOW");
                }
                actions.add("EDIT");
                actions.add("TABLES");
            }
            case "COMPLETED", "NO_SHOW" -> {
                if (!now.isAfter(r.startsAt().plus(Duration.ofHours(48)))) {
                    actions.add("CORRECT");
                }
                actions.add("NOTES");
            }
            default -> actions.add("NOTES");
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private List<ChangeEntry> changes(UUID id) {
        return jdbc.sql("""
                SELECT a.action, u.display_name, a.occurred_at, a.metadata::text
                  FROM audit_logs a LEFT JOIN app_users u ON u.id = a.actor_user_id
                 WHERE a.object_type = ? AND a.object_id = ? ORDER BY a.occurred_at, a.id
                """)
                .params(ReservationService.AGGREGATE, id.toString())
                .query((rs, n) -> new ChangeEntry(rs.getString(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), json.readValue(rs.getString(4), Map.class)))
                .list();
    }

    private String zoneName(UUID zoneId) {
        return zoneId == null ? null
                : jdbc.sql("SELECT name FROM capacity_zones WHERE id = ?").param(zoneId).query(String.class).optional().orElse(null);
    }

    private String userName(UUID userId) {
        return userId == null ? null
                : jdbc.sql("SELECT display_name FROM app_users WHERE id = ?").param(userId).query(String.class).optional().orElse(null);
    }
}
