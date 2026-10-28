package it.fireflies.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.capacity.OccupancyInterval;
import it.fireflies.common.BusinessTime;
import it.fireflies.dining.BookingCalendar;
import it.fireflies.dining.DiningStore;

/** Letture per la dashboard: elenco filtrato e situazione dei posti di un giorno, quanto per quanto. */
@Service
public class ReservationQueries {

    public record Result(List<ReservationViews.Summary> items, long total, Map<String, Long> counts) {
    }

    public record DayCapacity(LocalDate date, List<ZoneDay> zones) {
    }

    public record ZoneDay(UUID zoneId, String name, int physicalCapacity, List<QuantumView> quanta) {
    }

    /** Un quanto di 15 minuti: capienza effettiva, persone confermate, persone in attesa (solo informative), evento. */
    public record QuantumView(LocalTime time, int capacity, int confirmed, int pending, boolean event) {
    }

    private final ReservationStore store;
    private final ReservationViews views;
    private final CapacityLedger ledger;
    private final BookingCalendar calendar;

    ReservationQueries(ReservationStore store, ReservationViews views, CapacityLedger ledger, BookingCalendar calendar) {
        this.store = store;
        this.views = views;
        this.ledger = ledger;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public Result search(ReservationStore.Filter filter) {
        ReservationStore.Page page = store.search(filter);
        return new Result(views.summaries(page.items()), page.total(), page.counts());
    }

    @Transactional(readOnly = true)
    public DayCapacity day(LocalDate date) {
        List<DiningStore.WindowRow> windows = calendar.windowsOn(date);
        List<ZoneDay> zones = new ArrayList<>();
        for (DiningStore.ZoneRow zone : calendar.activeZones()) {
            List<DiningStore.WindowRow> own = windows.stream().filter(w -> w.zoneId().equals(zone.id())).toList();
            if (own.isEmpty()) {
                zones.add(new ZoneDay(zone.id(), zone.name(), zone.physicalCapacity(), List.of()));
                continue;
            }
            int first = own.stream().mapToInt(w -> w.firstStart().toSecondOfDay() / 60).min().orElseThrow();
            int last = Math.min(24 * 60, own.stream().mapToInt(DiningStore.WindowRow::coverageEndMinute).max().orElseThrow());
            Instant from = BusinessTime.toInstant(date, LocalTime.ofSecondOfDay(first * 60L));
            Instant to = last >= 24 * 60 ? BusinessTime.toInstant(date.plusDays(1), LocalTime.MIDNIGHT)
                    : BusinessTime.toInstant(date, LocalTime.ofSecondOfDay(last * 60L));
            List<QuantumView> quanta = new ArrayList<>();
            for (CapacityLedger.QuantumState q : ledger.read(zone.id(), from, to)) {
                Instant end = q.slotStart().plus(OccupancyInterval.QUANTUM);
                quanta.add(new QuantumView(q.slotStart().atZone(BusinessTime.ZONE).toLocalTime(), q.capacityCovers(),
                        q.occupiedCovers(), store.pendingCovers(zone.id(), q.slotStart(), end), q.exclusiveEventId() != null));
            }
            zones.add(new ZoneDay(zone.id(), zone.name(), zone.physicalCapacity(), quanta));
        }
        return new DayCapacity(date, zones);
    }
}
