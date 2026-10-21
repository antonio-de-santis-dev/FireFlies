package it.fireflies.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL delle prenotazioni, dello storico degli stati e delle assegnazioni dei tavoli. */
@Repository
class ReservationStore {

    record Row(UUID id, String publicRef, String status, String statusReason, String source, LocalDate reservationDate,
            Instant startsAt, Instant endsAt, Instant occupiedUntil, int guestCount, UUID serviceWindowId, UUID zoneId,
            String customerName, String customerPhone, String customerEmail, String customerNotes, String internalNote,
            boolean needsCallback, UUID idempotencyKey, String fingerprint, UUID createdBy, UUID handledBy,
            Instant confirmedAt, Instant closedAt, Instant createdAt, Instant updatedAt, int version, UUID eventId,
            String serviceBand) {

        boolean occupying() {
            return "CONFIRMED".equals(status) || "COMPLETED".equals(status) || "NO_SHOW".equals(status);
        }
    }

    record NewRow(String publicRef, String status, String source, LocalDate reservationDate, Instant startsAt, Instant endsAt,
            int guestCount, UUID serviceWindowId, UUID zoneId, String customerName, String customerPhone, String customerEmail,
            String customerNotes, String internalNote, String privacyVersion, Instant privacyAckAt, UUID idempotencyKey,
            String fingerprint, UUID createdBy, UUID eventId) {
    }

    record HistoryRow(String fromStatus, String toStatus, String reason, String note, UUID changedBy, String changedByName,
            Instant changedAt) {
    }

    record AssignmentRow(UUID id, UUID tableId, String tableCode, String areaName, int maxSeats, Instant from, Instant to,
            boolean active, boolean seatsOverride, String overrideReason) {
    }

    record Filter(LocalDate dateFrom, LocalDate dateTo, List<String> statuses, String source, String query,
            Boolean needsCallback, int page, int size) {
    }

    private static final String COLUMNS = """
            id, public_ref, status, status_reason, source, reservation_date, starts_at, ends_at, occupied_until,
            guest_count, service_window_id, capacity_zone_id, customer_name, customer_phone_e164, customer_email,
            customer_notes, internal_note, needs_callback, idempotency_key, request_fingerprint, created_by_user_id,
            handled_by_user_id, confirmed_at, closed_at, created_at, updated_at, version, event_id, service_band
            """;

    private final JdbcClient jdbc;

    ReservationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(NewRow r) {
        return jdbc.sql("""
                INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, guest_count,
                                          service_window_id, capacity_zone_id, customer_name, customer_phone_e164,
                                          customer_email, customer_notes, internal_note, privacy_notice_version,
                                          privacy_ack_at, idempotency_key, request_fingerprint, created_by_user_id, event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
                .params(r.publicRef(), r.status(), r.source(), r.reservationDate(), utc(r.startsAt()), utc(r.endsAt()),
                        r.guestCount(), r.serviceWindowId(), r.zoneId(), r.customerName(), r.customerPhone(),
                        r.customerEmail(), r.customerNotes(), r.internalNote(), r.privacyVersion(), utc(r.privacyAckAt()),
                        r.idempotencyKey(), r.fingerprint(), r.createdBy(), r.eventId())
                .query(UUID.class).single();
    }

    boolean refExists(String ref) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservations WHERE public_ref = ?)").param(ref).query(Boolean.class).single();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE id = ?").param(id).query(ReservationStore::row).optional();
    }

    Optional<Row> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE id = ? FOR UPDATE").param(id).query(ReservationStore::row).optional();
    }

    /** Lock di più prenotazioni in ordine di id (operazioni di massa, es. annullamento di un evento). */
    void lockAll(List<UUID> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("SELECT id FROM reservations WHERE id = ANY (?) ORDER BY id FOR UPDATE").param(ids.toArray(UUID[]::new))
                    .query(UUID.class).list();
        }
    }

    Optional<Row> byIdempotencyKey(UUID key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE idempotency_key = ?").param(key).query(ReservationStore::row).optional();
    }

    /**
     * FIX 2: c'è già una richiesta attiva (in attesa o confermata, da qualunque canale) dello stesso telefono nello
     * stesso giorno e nella stessa fascia? Solo sì/no: nessun dato dell'altra prenotazione esce da qui.
     */
    boolean activeSamePhoneBand(String phone, LocalDate date, String band) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reservations
                                WHERE customer_phone_e164 = ? AND reservation_date = ? AND service_band = ?
                                  AND status IN ('PENDING', 'CONFIRMED'))
                """).params(phone, date, band).query(Boolean.class).single();
    }

    long webRequestsFromPhoneSince(String phone, Instant since) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE source = 'WEB' AND customer_phone_e164 = ? AND created_at >= ?")
                .params(phone, utc(since)).query(Long.class).single();
    }

    /** Persone in attesa (PENDING) che si sovrappongono a un intervallo, per zona: solo informativo. */
    int pendingCovers(UUID zoneId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT coalesce(sum(guest_count), 0) FROM reservations
                 WHERE status = 'PENDING' AND capacity_zone_id = ? AND starts_at < ? AND ends_at > ?
                """).params(zoneId, utc(to), utc(from)).query(Integer.class).single();
    }

    /**
     * Cambio di stato. «Da richiamare» si spegne da solo appena la richiesta esce da «In attesa» (confermata o chiusa
     * in qualunque modo, FASE 7 req. 5): resta acceso solo finché c'è qualcosa da decidere col cliente; lo si può
     * riaccendere a mano dalla modifica.
     */
    void updateStatus(UUID id, String status, String reason, UUID zoneId, Instant occupiedUntil, UUID handledBy,
            boolean confirmed, boolean closed) {
        jdbc.sql("""
                UPDATE reservations
                   SET needs_callback = needs_callback AND ? = 'PENDING',
                       status = ?, status_reason = ?, capacity_zone_id = ?, occupied_until = ?, handled_by_user_id = ?,
                       confirmed_at = CASE WHEN ? THEN now() ELSE confirmed_at END,
                       closed_at = CASE WHEN ? THEN now() ELSE closed_at END,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                """)
                .params(status, status, reason, zoneId, utc(occupiedUntil), handledBy, confirmed, closed, id).update();
    }

    void updateDetails(UUID id, LocalDate date, Instant startsAt, Instant endsAt, Instant occupiedUntil, int guests,
            UUID windowId, UUID zoneId, String name, String phone, String email, String notes, String internalNote,
            boolean needsCallback, UUID handledBy) {
        jdbc.sql("""
                UPDATE reservations
                   SET reservation_date = ?, starts_at = ?, ends_at = ?, occupied_until = ?, guest_count = ?,
                       service_window_id = ?, capacity_zone_id = ?, customer_name = ?, customer_phone_e164 = ?,
                       customer_email = ?, customer_notes = ?, internal_note = ?, needs_callback = ?,
                       handled_by_user_id = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """)
                .params(date, utc(startsAt), utc(endsAt), utc(occupiedUntil), guests, windowId, zoneId, name, phone, email,
                        notes, internalNote, needsCallback, handledBy, id)
                .update();
    }

    void history(UUID id, String from, String to, String reason, String note, UUID actor) {
        jdbc.sql("""
                INSERT INTO reservation_status_history (reservation_id, from_status, to_status, reason, note, changed_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(id, from, to, reason, note, actor).update();
    }

    List<HistoryRow> historyOf(UUID id) {
        return jdbc.sql("""
                SELECT h.from_status, h.to_status, h.reason, h.note, h.changed_by_user_id, u.display_name, h.changed_at
                  FROM reservation_status_history h LEFT JOIN app_users u ON u.id = h.changed_by_user_id
                 WHERE h.reservation_id = ? ORDER BY h.changed_at, h.id
                """).param(id)
                .query((rs, n) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, OffsetDateTime.class).toInstant()))
                .list();
    }

    // --- tavoli -------------------------------------------------------------------------------------------------

    /** Lock delle righe dei tavoli in ordine di id (BOOKING_RULES §8, DECISION_LOG T-05): niente deadlock. */
    void lockTables(List<UUID> tableIds) {
        if (!tableIds.isEmpty()) {
            jdbc.sql("SELECT id FROM dining_tables WHERE id = ANY (?) ORDER BY id FOR NO KEY UPDATE")
                    .param(tableIds.toArray(UUID[]::new)).query(UUID.class).list();
        }
    }

    List<AssignmentRow> assignments(UUID reservationId, boolean onlyActive) {
        return jdbc.sql("""
                SELECT a.id, a.table_id, t.code, ar.name, t.max_seats, lower(a.occupied), upper(a.occupied), a.active,
                       a.seats_override, a.seats_override_reason
                  FROM reservation_table_assignments a
                  JOIN dining_tables t ON t.id = a.table_id JOIN dining_areas ar ON ar.id = t.area_id
                 WHERE a.reservation_id = ?
                """ + (onlyActive ? " AND a.active" : "") + " ORDER BY a.active DESC, t.code")
                .param(reservationId)
                .query((rs, n) -> new AssignmentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getObject(6, OffsetDateTime.class).toInstant(),
                        rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getBoolean(8), rs.getBoolean(9), rs.getString(10)))
                .list();
    }

    void releaseAssignments(UUID reservationId) {
        jdbc.sql("UPDATE reservation_table_assignments SET active = false, released_at = now() WHERE reservation_id = ? AND active")
                .param(reservationId).update();
    }

    void assign(UUID reservationId, UUID tableId, Instant from, Instant to, boolean override, UUID overrideBy,
            String overrideReason, UUID actor) {
        jdbc.sql("""
                INSERT INTO reservation_table_assignments (reservation_id, table_id, occupied, seats_override,
                                                           seats_override_by, seats_override_reason, assigned_by)
                VALUES (?, ?, tstzrange(?, ?, '[)'), ?, ?, ?, ?)
                """)
                .params(reservationId, tableId, utc(from), utc(to), override, overrideBy, overrideReason, actor).update();
    }

    /** Accorcia le assegnazioni attive (uscita anticipata, no-show): un intervallo più corto non può creare conflitti. */
    void shortenAssignments(UUID reservationId, Instant newEnd) {
        jdbc.sql("""
                UPDATE reservation_table_assignments SET occupied = tstzrange(lower(occupied), ?, '[)')
                 WHERE reservation_id = ? AND active
                """).params(utc(newEnd), reservationId).update();
    }

    // --- elenchi ------------------------------------------------------------------------------------------------

    record Page(List<Row> items, long total, Map<String, Long> counts) {
    }

    Page search(Filter f) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (f.dateFrom() != null) {
            where.append(" AND reservation_date >= ?");
            params.add(f.dateFrom());
        }
        if (f.dateTo() != null) {
            where.append(" AND reservation_date <= ?");
            params.add(f.dateTo());
        }
        if (f.source() != null) {
            where.append(" AND source = ?");
            params.add(f.source());
        }
        if (Boolean.TRUE.equals(f.needsCallback())) {
            where.append(" AND needs_callback");
        }
        if (f.query() != null && !f.query().isBlank()) {
            String q = "%" + f.query().strip().toLowerCase().replace("%", "").replace("_", "") + "%";
            String digits = f.query().replaceAll("[^0-9]", "");
            where.append(" AND (lower(public_ref) LIKE ? OR lower(customer_name) LIKE ? OR lower(coalesce(customer_email, '')) LIKE ?");
            params.add(q);
            params.add(q);
            params.add(q);
            if (digits.length() >= 3) {
                where.append(" OR customer_phone_e164 LIKE ?");
                params.add("%" + digits + "%");
            }
            where.append(")");
        }
        // conteggi per stato con gli stessi filtri (escluso lo stato), per i chip della pagina Richieste
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) FROM reservations" + where + " GROUP BY status").params(params)
                .query((ResultSet rs) -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.append(" AND status = ANY (?)");
            params.add(f.statuses().toArray(String[]::new));
        }
        long total = jdbc.sql("SELECT count(*) FROM reservations" + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(f.size());
        pageParams.add((long) f.page() * f.size());
        List<Row> items = jdbc.sql("SELECT " + COLUMNS + " FROM reservations" + where
                        + " ORDER BY (status = 'PENDING') DESC, starts_at, created_at LIMIT ? OFFSET ?")
                .params(pageParams).query(ReservationStore::row).list();
        return new Page(items, total, counts);
    }

    /** Richieste in attesa da far scadere (BOOKING_RULES §6), già bloccate e saltando quelle in mano a un gestore. */
    List<UUID> lockExpiring(Instant now, int minutesBeforeStart, int limit) {
        return jdbc.sql("""
                SELECT id FROM reservations
                 WHERE status = 'PENDING' AND starts_at - make_interval(mins => ?) <= ?
                 ORDER BY starts_at LIMIT ? FOR UPDATE SKIP LOCKED
                """).params(minutesBeforeStart, utc(now), limit).query(UUID.class).list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("public_ref"), rs.getString("status"),
                rs.getString("status_reason"), rs.getString("source"), rs.getObject("reservation_date", LocalDate.class),
                instant(rs, "starts_at"), instant(rs, "ends_at"), instant(rs, "occupied_until"), rs.getInt("guest_count"),
                rs.getObject("service_window_id", UUID.class), rs.getObject("capacity_zone_id", UUID.class),
                rs.getString("customer_name"), rs.getString("customer_phone_e164"), rs.getString("customer_email"),
                rs.getString("customer_notes"), rs.getString("internal_note"), rs.getBoolean("needs_callback"),
                rs.getObject("idempotency_key", UUID.class), rs.getString("request_fingerprint"),
                rs.getObject("created_by_user_id", UUID.class), rs.getObject("handled_by_user_id", UUID.class),
                instant(rs, "confirmed_at"), instant(rs, "closed_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("version"), rs.getObject("event_id", UUID.class), rs.getString("service_band"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
