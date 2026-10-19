package it.fireflies.capacity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL del registro di occupazione. I lock sono espliciti e sempre in ordine di orario:
 * è ciò che rende le conferme concorrenti sicure senza deadlock (spike 2, ADR-0006).
 */
@Repository
class CapacitySlotStore {

    record ZoneRow(UUID id, int physicalCapacity, boolean active) {
    }

    record SlotRow(Instant slotStart, int capacityCovers, int occupiedCovers, UUID exclusiveEventId) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    CapacitySlotStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Lock condiviso: più conferme sulla stessa zona procedono insieme, un cambio di capienza aspetta. */
    Optional<ZoneRow> lockZoneShared(UUID zoneId) {
        return jdbc.sql("SELECT id, physical_capacity, active FROM capacity_zones WHERE id = ? FOR SHARE")
                .param(zoneId)
                .query(CapacitySlotStore::zone)
                .optional();
    }

    /** Lock esclusivo per i cambi di capienza (NO KEY: non blocca le chiavi esterne verso la zona). */
    Optional<ZoneRow> lockZoneForUpdate(UUID zoneId) {
        return jdbc.sql("SELECT id, physical_capacity, active FROM capacity_zones WHERE id = ? FOR NO KEY UPDATE")
                .param(zoneId)
                .query(CapacitySlotStore::zone)
                .optional();
    }

    /** Crea i quanti mancanti in ordine crescente; quelli già presenti restano invariati. */
    void insertMissingSlots(UUID zoneId, List<Instant> quanta, Function<Instant, Integer> capacity) {
        List<Object[]> rows = quanta.stream()
                .map(q -> new Object[] {zoneId, utc(q), capacity.apply(q)})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers)
                VALUES (?, ?, ?)
                ON CONFLICT (zone_id, slot_start) DO NOTHING
                """, rows);
    }

    List<SlotRow> lockSlots(UUID zoneId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("""
                SELECT slot_start, capacity_covers, occupied_covers, exclusive_event_id
                  FROM capacity_slots
                 WHERE zone_id = ? AND slot_start >= ? AND slot_start < ?
                 ORDER BY slot_start
                   FOR UPDATE
                """)
                .params(zoneId, utc(fromInclusive), utc(toExclusive))
                .query(CapacitySlotStore::slot)
                .list();
    }

    List<SlotRow> lockSlotsFrom(UUID zoneId, Instant fromInclusive) {
        return jdbc.sql("""
                SELECT slot_start, capacity_covers, occupied_covers, exclusive_event_id
                  FROM capacity_slots
                 WHERE zone_id = ? AND slot_start >= ?
                 ORDER BY slot_start
                   FOR UPDATE
                """)
                .params(zoneId, utc(fromInclusive))
                .query(CapacitySlotStore::slot)
                .list();
    }

    void addOccupied(UUID zoneId, Map<Instant, Integer> deltas) {
        List<Object[]> rows = deltas.entrySet().stream()
                .map(e -> new Object[] {e.getValue(), zoneId, utc(e.getKey())})
                .toList();
        jdbcTemplate.batchUpdate("""
                UPDATE capacity_slots
                   SET occupied_covers = occupied_covers + ?, updated_at = now()
                 WHERE zone_id = ? AND slot_start = ?
                """, rows);
    }

    void updateCapacities(UUID zoneId, Map<Instant, Integer> capacities) {
        List<Object[]> rows = capacities.entrySet().stream()
                .map(e -> new Object[] {e.getValue(), zoneId, utc(e.getKey())})
                .toList();
        jdbcTemplate.batchUpdate("""
                UPDATE capacity_slots
                   SET capacity_covers = ?, updated_at = now()
                 WHERE zone_id = ? AND slot_start = ?
                """, rows);
    }

    void updatePhysicalCapacity(UUID zoneId, int physicalCapacity) {
        jdbc.sql("""
                UPDATE capacity_zones
                   SET physical_capacity = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """)
                .params(physicalCapacity, zoneId)
                .update();
    }

    private static ZoneRow zone(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ZoneRow(rs.getObject("id", UUID.class), rs.getInt("physical_capacity"), rs.getBoolean("active"));
    }

    private static SlotRow slot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new SlotRow(rs.getObject("slot_start", OffsetDateTime.class).toInstant(),
                rs.getInt("capacity_covers"), rs.getInt("occupied_covers"),
                rs.getObject("exclusive_event_id", UUID.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
