package it.fireflies.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.capacity.Occupancy;
import it.fireflies.capacity.OccupancyInterval;
import it.fireflies.common.BusinessTime;

/**
 * Simula, solo nei test, le transazioni che il modulo prenotazioni scriverà in FASE 5:
 * riga della prenotazione e registro di occupazione aggiornati nella stessa transazione.
 */
@TestComponent
public class BookingFixtures {

    public static final LocalDate DAY = LocalDate.of(2030, 5, 10);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CapacityLedger ledger;

    public BookingFixtures(JdbcClient jdbc, TransactionTemplate tx, CapacityLedger ledger) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
    }

    public static Instant at(String localTime) {
        return BusinessTime.toInstant(DAY, LocalTime.parse(localTime));
    }

    public UUID zone(String name, int physicalCapacity) {
        return jdbc.sql("INSERT INTO capacity_zones (name, physical_capacity, active) VALUES (?, ?, true) RETURNING id")
                .params(name, physicalCapacity).query(UUID.class).single();
    }

    public UUID window(UUID zoneId) {
        return jdbc.sql("""
                INSERT INTO service_windows (zone_id, iso_day, name, first_start, last_start, slot_minutes,
                                             default_duration_minutes, valid_from)
                VALUES (?, 5, 'Aperitivo', '18:00', '21:00', 30, 120, DATE '2030-01-01') RETURNING id
                """).param(zoneId).query(UUID.class).single();
    }

    /** Prenotazione inserita da telefono e confermata subito: riga + occupazione in un'unica transazione. */
    public UUID confirm(UUID zoneId, UUID windowId, Instant start, Duration length, int guests) {
        OccupancyInterval interval = OccupancyInterval.of(start, length);
        return tx.execute(status -> {
            UUID id = jdbc.sql("""
                    INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at,
                                              occupied_until, guest_count, service_window_id, capacity_zone_id,
                                              customer_name, customer_phone_e164, confirmed_at)
                    VALUES (?, 'CONFIRMED', 'PHONE', ?, ?, ?, ?, ?, ?, ?, 'Cliente demo', '+390000000001', now())
                    RETURNING id
                    """)
                    .params(publicRef(), BusinessTime.serviceDate(start), utc(interval.start()), utc(interval.end()),
                            utc(interval.end()), guests, windowId, zoneId)
                    .query(UUID.class).single();
            ledger.occupy(new Occupancy(zoneId, interval, guests));
            return id;
        });
    }

    /** Annullamento di una confermata: stato + rilascio dei posti. */
    public void cancel(UUID reservationId) {
        tx.executeWithoutResult(status -> {
            Row r = lock(reservationId);
            jdbc.sql("UPDATE reservations SET status = 'CANCELLED', closed_at = now(), version = version + 1 WHERE id = ?")
                    .param(reservationId).update();
            ledger.release(new Occupancy(r.zoneId(), new OccupancyInterval(r.start(), r.occupiedUntil()), r.guests()));
        });
    }

    /** Modifica di una confermata: nuovo orario/durata/persone/zona; se non sta, nulla cambia. */
    public void change(UUID reservationId, UUID newZoneId, Instant newStart, Duration newLength, int newGuests) {
        OccupancyInterval after = OccupancyInterval.of(newStart, newLength);
        tx.executeWithoutResult(status -> {
            Row r = lock(reservationId);
            jdbc.sql("""
                    UPDATE reservations
                       SET capacity_zone_id = ?, starts_at = ?, ends_at = ?, occupied_until = ?, guest_count = ?,
                           reservation_date = ?, version = version + 1
                     WHERE id = ?
                    """)
                    .params(newZoneId, utc(after.start()), utc(after.end()), utc(after.end()), newGuests,
                            BusinessTime.serviceDate(newStart), reservationId)
                    .update();
            ledger.change(new Occupancy(r.zoneId(), new OccupancyInterval(r.start(), r.occupiedUntil()), r.guests()),
                    new Occupancy(newZoneId, after, newGuests));
        });
    }

    /** Uscita anticipata: i quanti da {@code freeFrom} in poi tornano liberi. */
    public void completeEarly(UUID reservationId, Instant freeFrom) {
        tx.executeWithoutResult(status -> {
            Row r = lock(reservationId);
            jdbc.sql("UPDATE reservations SET status = 'COMPLETED', occupied_until = ?, version = version + 1 WHERE id = ?")
                    .params(utc(freeFrom), reservationId).update();
            ledger.release(new Occupancy(r.zoneId(), new OccupancyInterval(freeFrom, r.occupiedUntil()), r.guests()));
        });
    }

    public void changeZoneCapacity(UUID zoneId, int capacity) {
        tx.executeWithoutResult(status -> ledger.changePhysicalCapacity(zoneId, capacity, at("00:00")));
    }

    public int occupiedAt(UUID zoneId, Instant slot) {
        return jdbc.sql("SELECT occupied_covers FROM capacity_slots WHERE zone_id = ? AND slot_start = ?")
                .params(zoneId, utc(slot)).query(Integer.class).optional().orElse(0);
    }

    public long confirmedCount() {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE status = 'CONFIRMED'").query(Long.class).single();
    }

    private record Row(UUID zoneId, Instant start, Instant occupiedUntil, int guests) {
    }

    private Row lock(UUID id) {
        return jdbc.sql("""
                SELECT capacity_zone_id, starts_at, occupied_until, guest_count
                  FROM reservations WHERE id = ? FOR UPDATE
                """)
                .param(id)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getInt(4)))
                .single();
    }

    private static String publicRef() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("FF-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
