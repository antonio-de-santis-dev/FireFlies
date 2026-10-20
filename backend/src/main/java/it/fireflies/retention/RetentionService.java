package it.fireflies.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import it.fireflies.common.BusinessTime;
import it.fireflies.dining.Band;
import it.fireflies.notification.NotificationDispatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Pulizia notturna coordinata (FASE 7, req. 4, 5, 12). Ogni passo lavora a lotti in transazioni brevi con
 * {@code FOR UPDATE SKIP LOCKED}: una riga che un gestore sta modificando si salta e si riprende la notte dopo.
 * <ol>
 * <li>Prenotazioni finite da almeno un mese di calendario (qualunque stato, mai future né in corso), salvo quelle con
 * notifiche ancora da inviare: prima i contatori anonimi in {@code reservation_stats}, poi storico stati,
 * assegnazioni dei tavoli e notifiche della prenotazione (contengono telefono o email), infine la prenotazione.</li>
 * <li>Quanti del registro anteriori allo stesso limite senza prenotazioni rimaste (il registro resta coerente).</li>
 * <li>Eventi finiti da almeno due mesi di calendario senza più prenotazioni: riserva esclusiva liberata, foto
 * collegate staccate (le foto restano nella libreria), evento eliminato.</li>
 * <li>Notifiche in stato finale oltre il periodo di conservazione (i tentativi vanno con loro).</li>
 * <li>Audit oltre il periodo di conservazione (default 12 mesi).</li>
 * </ol>
 * L'audit delle operazioni non viene toccato dai passi 1–4: resta la traccia di chi ha fatto cosa (senza dati personali).
 */
@Service
public class RetentionService {

    public static final String JOB = "NIGHTLY_RETENTION";
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH = 500;

    public record Report(LocalDate runDate, boolean executed, Map<String, Integer> deleted) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationDispatcher notifications;
    private final Clock clock;
    private final ObjectMapper json;
    private final int reservationMonths;
    private final int eventMonths;
    private final int outboxDays;
    private final int auditMonths;

    RetentionService(JdbcClient jdbc, TransactionTemplate tx, NotificationDispatcher notifications, Clock clock, ObjectMapper json,
            @Value("${fireflies.retention.reservation-months:1}") int reservationMonths,
            @Value("${fireflies.retention.event-months:2}") int eventMonths,
            @Value("${fireflies.retention.outbox-days:90}") int outboxDays,
            @Value("${fireflies.retention.audit-months:12}") int auditMonths) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifications = notifications;
        this.clock = clock;
        this.json = json;
        this.reservationMonths = reservationMonths;
        this.eventMonths = eventMonths;
        this.outboxDays = outboxDays;
        this.auditMonths = auditMonths;
    }

    /**
     * Esegue la pulizia del giorno se non è già stata fatta (idempotente). Il «biglietto» è la riga
     * {@code maintenance_runs (job, giorno)}: la prende un solo processo; se un tentativo fallisce resta FAILED e il
     * tentativo dell'ora successiva la riprende; un RUNNING più vecchio di due ore (processo morto) si può riprendere.
     */
    public Report runIfDue() {
        LocalDate runDate = BusinessTime.serviceDate(Instant.now(clock));
        boolean claimed = Boolean.TRUE.equals(tx.execute(s -> jdbc.sql("""
                INSERT INTO maintenance_runs (job, run_date, status) VALUES (?, ?, 'RUNNING')
                ON CONFLICT (job, run_date) DO UPDATE
                   SET status = 'RUNNING', attempts = maintenance_runs.attempts + 1, started_at = now(), last_error = NULL
                 WHERE maintenance_runs.status = 'FAILED'
                    OR (maintenance_runs.status = 'RUNNING' AND maintenance_runs.started_at < now() - interval '2 hours')
                RETURNING true
                """).params(JOB, runDate).query(Boolean.class).optional().orElse(false)));
        if (!claimed) {
            return new Report(runDate, false, Map.of());
        }
        try {
            Map<String, Integer> deleted = run(runDate);
            tx.executeWithoutResult(s -> jdbc.sql("""
                    UPDATE maintenance_runs SET status = 'DONE', finished_at = now(), details = ?::jsonb WHERE job = ? AND run_date = ?
                    """).params(json.writeValueAsString(deleted), JOB, runDate).update());
            log.info("Pulizia notturna {}: {}", runDate, deleted);
            return new Report(runDate, true, deleted);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            tx.executeWithoutResult(s -> jdbc.sql("""
                    UPDATE maintenance_runs SET status = 'FAILED', finished_at = now(), last_error = ? WHERE job = ? AND run_date = ?
                    """).params(error.length() > 300 ? error.substring(0, 300) : error, JOB, runDate).update());
            log.error("Pulizia notturna {} non riuscita, si riprova al prossimo orario: {}", runDate, error, e);
            throw e;
        }
    }

    /** I passi nell'ordine; ognuno a lotti finché non resta nulla. Pubblico per i test. */
    public Map<String, Integer> run(LocalDate today) {
        Instant now = Instant.now(clock);
        LocalDate reservationCutoff = today.minusMonths(reservationMonths);
        Instant reservationLimit = BusinessTime.toInstant(reservationCutoff, LocalTime.MIDNIGHT);
        Instant eventLimit = BusinessTime.toInstant(today.minusMonths(eventMonths), LocalTime.MIDNIGHT);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        // notifiche ormai inutili (prenotazione passata): saltate, così non trattengono la prenotazione
        deleted.put("notificationsExpired", notifications.expireStale());
        deleted.put("reservations", loop(() -> reservationBatch(reservationCutoff, now)));
        deleted.put("capacitySlots", loop(() -> slotBatch(reservationLimit)));
        deleted.put("events", loop(() -> eventBatch(eventLimit, now)));
        deleted.put("notifications", loop(() -> outboxBatch(now.minus(java.time.Duration.ofDays(outboxDays)), now)));
        deleted.put("auditEntries", loop(() -> auditBatch(now.atZone(BusinessTime.ZONE).minusMonths(auditMonths).toInstant())));
        return deleted;
    }

    private static int loop(java.util.function.IntSupplier batch) {
        int total = 0;
        for (int n = batch.getAsInt(); n > 0; n = batch.getAsInt()) {
            total += n;
            if (n < BATCH) {
                break;
            }
        }
        return total;
    }

    // ---- 1. prenotazioni ------------------------------------------------------------------------------------

    private record Old(UUID id, LocalDate date, Instant startsAt, Instant endsAt, String status, String reason, String source,
            int guests, String windowName, UUID eventId, UUID zoneId) {

        boolean occupied() {
            return status.equals("CONFIRMED") || status.equals("COMPLETED") || status.equals("NO_SHOW");
        }
    }

    private int reservationBatch(LocalDate cutoff, Instant now) {
        Integer n = tx.execute(s -> {
            List<Old> rows = jdbc.sql("""
                    SELECT r.id, r.reservation_date, r.starts_at, coalesce(r.occupied_until, r.ends_at), r.status,
                           r.status_reason, r.source, r.guest_count, w.name, r.event_id, r.capacity_zone_id
                      FROM reservations r LEFT JOIN service_windows w ON w.id = r.service_window_id
                     WHERE r.reservation_date < ? AND r.ends_at < ?
                       AND NOT EXISTS (SELECT 1 FROM notification_outbox o
                                        WHERE o.aggregate_type = 'RESERVATION' AND o.aggregate_id = r.id
                                          AND o.state IN ('PENDING', 'SENDING'))
                     ORDER BY r.id LIMIT ? FOR UPDATE OF r SKIP LOCKED
                    """).params(cutoff, utc(now), BATCH).query((rs, i) -> new Old(rs.getObject(1, UUID.class),
                            rs.getObject(2, LocalDate.class), instant(rs, 3), instant(rs, 4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getInt(8), rs.getString(9), rs.getObject(10, UUID.class),
                            rs.getObject(11, UUID.class))).list();
            if (rows.isEmpty()) {
                return 0;
            }
            aggregate(rows);
            UUID[] ids = rows.stream().map(Old::id).toArray(UUID[]::new);
            jdbc.sql("DELETE FROM notification_outbox WHERE aggregate_type = 'RESERVATION' AND aggregate_id = ANY (?)").param(ids).update();
            jdbc.sql("DELETE FROM reservation_table_assignments WHERE reservation_id = ANY (?)").param(ids).update();
            jdbc.sql("DELETE FROM reservation_status_history WHERE reservation_id = ANY (?)").param(ids).update();
            // posti ancora occupati nel registro da prenotazioni passate: si tolgono insieme (il registro resta coerente)
            for (Old r : rows) {
                if (r.zoneId() != null && r.occupied()) {
                    jdbc.sql("""
                            UPDATE capacity_slots SET occupied_covers = greatest(0, occupied_covers - ?), updated_at = now()
                             WHERE zone_id = ? AND slot_start >= ? AND slot_start < ?
                            """).params(r.guests(), r.zoneId(), utc(r.startsAt()), utc(r.endsAt())).update();
                }
                if (r.eventId() != null && r.occupied()) {
                    jdbc.sql("UPDATE events SET confirmed_covers = greatest(0, confirmed_covers - ?) WHERE id = ?")
                            .params(r.guests(), r.eventId()).update();
                }
            }
            return jdbc.sql("DELETE FROM reservations WHERE id = ANY (?)").param(ids).update();
        });
        return n == null ? 0 : n;
    }

    /** Contatori anonimi per giorno e fascia, sommati a quelli già presenti (lotti diversi dello stesso giorno). */
    private void aggregate(List<Old> rows) {
        Map<String, int[]> groups = new HashMap<>();
        Map<String, Integer> capacity = new HashMap<>();
        for (Old r : rows) {
            Band band = Band.of(r.windowName(), r.startsAt().atZone(BusinessTime.ZONE).toLocalTime());
            String key = r.date() + "|" + band.name();
            int[] c = groups.computeIfAbsent(key, k -> new int[9]);
            c[0]++;
            if (r.occupied()) {
                c[1]++;
                c[2] += r.guests();
            }
            if (r.status().equals("REJECTED")) {
                c["EXPIRED".equals(r.reason()) ? 5 : 3]++; // la scadenza automatica è un rifiuto con motivo EXPIRED
            } else if (r.status().equals("CANCELLED")) {
                c[4]++;
            } else if (r.status().equals("NO_SHOW")) {
                c[6]++;
            }
            if ("WEB".equals(r.source())) {
                c[7]++;
            }
            if (r.eventId() != null) {
                c[8]++;
            }
            if (r.occupied() && r.zoneId() != null) {
                Optional<Integer> cap = jdbc.sql("""
                        SELECT min(capacity_covers) FROM capacity_slots WHERE zone_id = ? AND slot_start >= ? AND slot_start < ?
                        """).params(r.zoneId(), utc(r.startsAt()), utc(r.endsAt())).query(Integer.class).optional();
                cap.ifPresent(v -> capacity.merge(key, v, Math::min));
            }
        }
        groups.forEach((key, c) -> {
            String[] parts = key.split("\\|");
            jdbc.sql("""
                    INSERT INTO reservation_stats (service_date, band, requests, confirmed, confirmed_covers, rejected, cancelled,
                                                   expired, no_show, from_web, for_events, capacity_covers)
                    VALUES (?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (service_date, band) DO UPDATE SET
                       requests = reservation_stats.requests + EXCLUDED.requests,
                       confirmed = reservation_stats.confirmed + EXCLUDED.confirmed,
                       confirmed_covers = reservation_stats.confirmed_covers + EXCLUDED.confirmed_covers,
                       rejected = reservation_stats.rejected + EXCLUDED.rejected,
                       cancelled = reservation_stats.cancelled + EXCLUDED.cancelled,
                       expired = reservation_stats.expired + EXCLUDED.expired,
                       no_show = reservation_stats.no_show + EXCLUDED.no_show,
                       from_web = reservation_stats.from_web + EXCLUDED.from_web,
                       for_events = reservation_stats.for_events + EXCLUDED.for_events,
                       capacity_covers = coalesce(least(reservation_stats.capacity_covers, EXCLUDED.capacity_covers),
                                                  reservation_stats.capacity_covers, EXCLUDED.capacity_covers),
                       updated_at = now()
                    """).params(parts[0], parts[1], c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], capacity.get(key))
                    .update();
        });
    }

    // ---- 2. quanti del registro -------------------------------------------------------------------------------

    private int slotBatch(Instant limit) {
        Integer n = tx.execute(s -> jdbc.sql("""
                DELETE FROM capacity_slots WHERE (zone_id, slot_start) IN (
                    SELECT c.zone_id, c.slot_start FROM capacity_slots c
                     WHERE c.slot_start < ?
                       AND NOT EXISTS (SELECT 1 FROM reservations r
                                        WHERE r.capacity_zone_id = c.zone_id AND r.starts_at <= c.slot_start
                                          AND r.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
                                          AND coalesce(r.occupied_until, r.ends_at) > c.slot_start)
                     ORDER BY c.zone_id, c.slot_start LIMIT ? FOR UPDATE SKIP LOCKED)
                """).params(utc(limit), BATCH).update());
        return n == null ? 0 : n;
    }

    // ---- 3. eventi ------------------------------------------------------------------------------------------

    private int eventBatch(Instant limit, Instant now) {
        Integer n = tx.execute(s -> {
            List<UUID> ids = jdbc.sql("""
                    SELECT e.id FROM events e
                     WHERE e.starts_at IS NOT NULL AND coalesce(e.ends_at, e.starts_at) < ? AND coalesce(e.ends_at, e.starts_at) < ?
                       AND NOT EXISTS (SELECT 1 FROM reservations r WHERE r.event_id = e.id)
                     ORDER BY e.id LIMIT ? FOR UPDATE SKIP LOCKED
                    """).params(utc(limit), utc(now), BATCH).query(UUID.class).list();
            if (ids.isEmpty()) {
                return 0;
            }
            UUID[] array = ids.toArray(UUID[]::new);
            jdbc.sql("UPDATE capacity_slots SET exclusive_event_id = NULL, updated_at = now() WHERE exclusive_event_id = ANY (?)")
                    .param(array).update();
            return jdbc.sql("DELETE FROM events WHERE id = ANY (?)").param(array).update(); // event_media in cascata
        });
        return n == null ? 0 : n;
    }

    // ---- 4. notifiche ---------------------------------------------------------------------------------------

    private int outboxBatch(Instant olderThan, Instant now) {
        Integer n = tx.execute(s -> jdbc.sql("""
                DELETE FROM notification_outbox WHERE id IN (
                    SELECT id FROM notification_outbox
                     WHERE state IN ('SENT', 'DELIVERED', 'DEAD', 'SKIPPED')
                       AND (purge_after IS NOT NULL AND purge_after < ? OR purge_after IS NULL AND created_at < ?)
                     ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                """).params(utc(now), utc(olderThan), BATCH).update());
        return n == null ? 0 : n;
    }

    // ---- 5. audit -------------------------------------------------------------------------------------------

    private int auditBatch(Instant olderThan) {
        Integer n = tx.execute(s -> jdbc.sql("""
                DELETE FROM audit_logs WHERE id IN (SELECT id FROM audit_logs WHERE occurred_at < ? ORDER BY id LIMIT ?)
                """).params(utc(olderThan), BATCH).update());
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Esiti delle ultime esecuzioni (per i controlli e i test). */
    public List<Map<String, Object>> lastRuns(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.sql("SELECT run_date, status, attempts, details::text, last_error FROM maintenance_runs WHERE job = ? ORDER BY run_date DESC LIMIT ?")
                .params(JOB, limit).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runDate", rs.getObject(1, LocalDate.class));
                    m.put("status", rs.getString(2));
                    m.put("attempts", rs.getInt(3));
                    m.put("details", rs.getString(4));
                    m.put("lastError", rs.getString(5));
                    return out.add(m);
                }).list();
        return out;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
