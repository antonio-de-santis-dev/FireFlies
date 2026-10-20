package it.fireflies.dining;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL della configurazione delle prenotazioni: zone, aree, tavoli, fasce, regole generali, eccezioni. */
@Repository
public class DiningStore {

    public record ZoneRow(UUID id, String name, int physicalCapacity, boolean active, int sortOrder, int version) {
    }

    public record AreaRow(UUID id, UUID zoneId, String name, boolean active, int sortOrder) {
    }

    public record TableRow(UUID id, UUID areaId, UUID zoneId, String code, int minSeats, int maxSeats, boolean active, int sortOrder) {
    }

    public record WindowRow(UUID id, UUID zoneId, int isoDay, String name, LocalTime firstStart, LocalTime lastStart,
            int slotMinutes, int defaultDurationMinutes, Integer maxCovers, LocalDate validFrom, LocalDate validTo,
            boolean active) {

        /** Fine dell'ultima permanenza standard: i quanti fino a qui appartengono alla fascia (BOOKING_RULES §3.2). */
        /** Minuti dall'inizio della giornata in cui finisce l'ultima permanenza standard (max 24:00). */
        public int coverageEndMinute() {
            return lastStart.toSecondOfDay() / 60 + defaultDurationMinutes;
        }

        /** Il quanto che inizia a quest'ora appartiene alla fascia (BOOKING_RULES §3.2). */
        public boolean covers(LocalTime time) {
            int minute = time.toSecondOfDay() / 60;
            return minute >= firstStart.toSecondOfDay() / 60 && minute < coverageEndMinute();
        }

        public boolean validOn(LocalDate date) {
            return active && !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo))
                    && date.getDayOfWeek().getValue() == isoDay;
        }
    }

    public record PolicyRow(UUID id, LocalDate effectiveFrom, boolean webBookingEnabled, int minLeadMinutes, int maxAdvanceDays,
            int maxPartySizeWeb, int pendingExpiryMinutesBeforeStart) {
    }

    public record ExceptionRow(UUID id, LocalDate serviceDate, UUID serviceWindowId, String kind, Integer capacityOverride,
            String reason) {
    }

    private final JdbcClient jdbc;

    DiningStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- zone ---------------------------------------------------------------------------------------------------

    List<ZoneRow> zones() {
        return jdbc.sql("SELECT id, name, physical_capacity, active, sort_order, version FROM capacity_zones ORDER BY sort_order, name")
                .query(DiningStore::zone).list();
    }

    Optional<ZoneRow> zone(UUID id) {
        return jdbc.sql("SELECT id, name, physical_capacity, active, sort_order, version FROM capacity_zones WHERE id = ?")
                .param(id).query(DiningStore::zone).optional();
    }

    UUID insertZone(String name, int sortOrder) {
        return jdbc.sql("INSERT INTO capacity_zones (name, physical_capacity, active, sort_order) VALUES (?, 0, false, ?) RETURNING id")
                .params(name, sortOrder).query(UUID.class).single();
    }

    boolean updateZone(UUID id, int version, String name, boolean active, int sortOrder) {
        return jdbc.sql("""
                UPDATE capacity_zones SET name = ?, active = ?, sort_order = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """).params(name, active, sortOrder, id, version).update() == 1;
    }

    boolean futureConfirmedInZone(UUID zoneId, java.time.Instant now) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reservations
                                WHERE capacity_zone_id = ? AND status = 'CONFIRMED' AND occupied_until > ?)
                """).params(zoneId, now.atOffset(java.time.ZoneOffset.UTC)).query(Boolean.class).single();
    }

    // --- aree e tavoli ------------------------------------------------------------------------------------------

    List<AreaRow> areas() {
        return jdbc.sql("SELECT id, zone_id, name, active, sort_order FROM dining_areas ORDER BY sort_order, name")
                .query((rs, n) -> new AreaRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBoolean(4), rs.getInt(5)))
                .list();
    }

    UUID insertArea(UUID zoneId, String name, int sortOrder) {
        return jdbc.sql("INSERT INTO dining_areas (zone_id, name, sort_order) VALUES (?, ?, ?) RETURNING id")
                .params(zoneId, name, sortOrder).query(UUID.class).single();
    }

    boolean updateArea(UUID id, UUID zoneId, String name, boolean active, int sortOrder) {
        return jdbc.sql("UPDATE dining_areas SET zone_id = ?, name = ?, active = ?, sort_order = ? WHERE id = ?")
                .params(zoneId, name, active, sortOrder, id).update() == 1;
    }

    boolean areaHasTables(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM dining_tables WHERE area_id = ?)").param(id).query(Boolean.class).single();
    }

    boolean deleteArea(UUID id) {
        return jdbc.sql("DELETE FROM dining_areas WHERE id = ?").param(id).update() == 1;
    }

    List<TableRow> tables() {
        return jdbc.sql("""
                SELECT t.id, t.area_id, a.zone_id, t.code, t.min_seats, t.max_seats, t.active, t.sort_order
                  FROM dining_tables t JOIN dining_areas a ON a.id = t.area_id
                 ORDER BY a.sort_order, t.sort_order, t.code
                """).query(DiningStore::table).list();
    }

    UUID insertTable(UUID areaId, String code, int minSeats, int maxSeats, int sortOrder) {
        return jdbc.sql("INSERT INTO dining_tables (area_id, code, min_seats, max_seats, sort_order) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(areaId, code, minSeats, maxSeats, sortOrder).query(UUID.class).single();
    }

    boolean updateTable(UUID id, UUID areaId, String code, int minSeats, int maxSeats, boolean active, int sortOrder) {
        return jdbc.sql("""
                UPDATE dining_tables SET area_id = ?, code = ?, min_seats = ?, max_seats = ?, active = ?, sort_order = ?
                 WHERE id = ?
                """).params(areaId, code, minSeats, maxSeats, active, sortOrder, id).update() == 1;
    }

    boolean tableHasHistory(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservation_table_assignments WHERE table_id = ?)")
                .param(id).query(Boolean.class).single();
    }

    boolean deleteTable(UUID id) {
        return jdbc.sql("DELETE FROM dining_tables WHERE id = ?").param(id).update() == 1;
    }

    void deactivateTable(UUID id) {
        jdbc.sql("UPDATE dining_tables SET active = false WHERE id = ?").param(id).update();
    }

    // --- fasce --------------------------------------------------------------------------------------------------

    List<WindowRow> windows() {
        return jdbc.sql("""
                SELECT id, zone_id, iso_day, name, first_start, last_start, slot_minutes, default_duration_minutes,
                       max_covers, valid_from, valid_to, active
                  FROM service_windows ORDER BY iso_day, first_start, name
                """).query(DiningStore::window).list();
    }

    List<WindowRow> windowsOfZone(UUID zoneId) {
        return windows().stream().filter(w -> w.zoneId().equals(zoneId)).toList();
    }

    Optional<WindowRow> window(UUID id) {
        return windows().stream().filter(w -> w.id().equals(id)).findFirst();
    }

    UUID insertWindow(WindowRow w) {
        return jdbc.sql("""
                INSERT INTO service_windows (zone_id, iso_day, name, first_start, last_start, slot_minutes,
                                             default_duration_minutes, max_covers, valid_from, valid_to, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
                .params(w.zoneId(), w.isoDay(), w.name(), w.firstStart(), w.lastStart(), w.slotMinutes(),
                        w.defaultDurationMinutes(), w.maxCovers(), w.validFrom(), w.validTo(), w.active())
                .query(UUID.class).single();
    }

    void updateWindow(WindowRow w) {
        jdbc.sql("""
                UPDATE service_windows
                   SET zone_id = ?, iso_day = ?, name = ?, first_start = ?, last_start = ?, slot_minutes = ?,
                       default_duration_minutes = ?, max_covers = ?, valid_from = ?, valid_to = ?, active = ?
                 WHERE id = ?
                """)
                .params(w.zoneId(), w.isoDay(), w.name(), w.firstStart(), w.lastStart(), w.slotMinutes(),
                        w.defaultDurationMinutes(), w.maxCovers(), w.validFrom(), w.validTo(), w.active(), w.id())
                .update();
    }

    boolean windowHasReservations(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservations WHERE service_window_id = ?)").param(id).query(Boolean.class).single();
    }

    void deleteWindow(UUID id) {
        jdbc.sql("DELETE FROM service_windows WHERE id = ?").param(id).update();
    }

    // --- regole generali ----------------------------------------------------------------------------------------

    Optional<PolicyRow> policyOn(LocalDate date) {
        return jdbc.sql("""
                SELECT id, effective_from, web_booking_enabled, min_lead_minutes, max_advance_days, max_party_size_web,
                       pending_expiry_minutes_before_start
                  FROM booking_policies WHERE effective_from <= ? ORDER BY effective_from DESC LIMIT 1
                """).param(date).query(DiningStore::policy).optional();
    }

    void upsertPolicy(LocalDate effectiveFrom, boolean web, int lead, int advance, int party, int expiry, UUID actor) {
        jdbc.sql("""
                INSERT INTO booking_policies (effective_from, web_booking_enabled, min_lead_minutes, max_advance_days,
                                              max_party_size_web, pending_expiry_minutes_before_start, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (effective_from) DO UPDATE
                   SET web_booking_enabled = EXCLUDED.web_booking_enabled, min_lead_minutes = EXCLUDED.min_lead_minutes,
                       max_advance_days = EXCLUDED.max_advance_days, max_party_size_web = EXCLUDED.max_party_size_web,
                       pending_expiry_minutes_before_start = EXCLUDED.pending_expiry_minutes_before_start,
                       created_by = EXCLUDED.created_by, created_at = now()
                """)
                .params(effectiveFrom, web, lead, advance, party, expiry, actor).update();
    }

    // --- eccezioni ----------------------------------------------------------------------------------------------

    List<ExceptionRow> exceptionsFrom(LocalDate from) {
        return jdbc.sql("""
                SELECT id, service_date, service_window_id, kind, capacity_override, reason
                  FROM booking_exceptions WHERE service_date >= ? ORDER BY service_date, service_window_id NULLS FIRST
                """).param(from).query(DiningStore::exception).list();
    }

    List<ExceptionRow> exceptionsOn(LocalDate date) {
        return jdbc.sql("""
                SELECT id, service_date, service_window_id, kind, capacity_override, reason
                  FROM booking_exceptions WHERE service_date = ?
                """).param(date).query(DiningStore::exception).list();
    }

    Optional<ExceptionRow> exception(UUID id) {
        return jdbc.sql("SELECT id, service_date, service_window_id, kind, capacity_override, reason FROM booking_exceptions WHERE id = ?")
                .param(id).query(DiningStore::exception).optional();
    }

    UUID insertException(LocalDate date, UUID windowId, String kind, Integer override, String reason, UUID actor) {
        return jdbc.sql("""
                INSERT INTO booking_exceptions (service_date, service_window_id, kind, capacity_override, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """).params(date, windowId, kind, override, reason, actor).query(UUID.class).single();
    }

    void deleteException(UUID id) {
        jdbc.sql("DELETE FROM booking_exceptions WHERE id = ?").param(id).update();
    }

    private static ZoneRow zone(ResultSet rs, int n) throws SQLException {
        return new ZoneRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("physical_capacity"),
                rs.getBoolean("active"), rs.getInt("sort_order"), rs.getInt("version"));
    }

    private static TableRow table(ResultSet rs, int n) throws SQLException {
        return new TableRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getBoolean(7), rs.getInt(8));
    }

    private static WindowRow window(ResultSet rs, int n) throws SQLException {
        return new WindowRow(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getInt("iso_day"),
                rs.getString("name"), rs.getObject("first_start", LocalTime.class), rs.getObject("last_start", LocalTime.class),
                rs.getInt("slot_minutes"), rs.getInt("default_duration_minutes"), (Integer) rs.getObject("max_covers"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getBoolean("active"));
    }

    private static PolicyRow policy(ResultSet rs, int n) throws SQLException {
        return new PolicyRow(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getBoolean(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7));
    }

    private static ExceptionRow exception(ResultSet rs, int n) throws SQLException {
        return new ExceptionRow(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getObject(3, UUID.class),
                rs.getString(4), (Integer) rs.getObject(5), rs.getString(6));
    }
}
