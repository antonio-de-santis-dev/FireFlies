package it.fireflies.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.BusinessTime;
import it.fireflies.dining.Band;
import it.fireflies.dining.BookingCalendar;
import it.fireflies.dining.DiningStore;

/**
 * Viste operative dei gestori (FASE 7): il calendario delle prenotazioni confermate di oggi (req. 9) e il riepilogo
 * per giorno e fascia con richieste, confermate, persone, capienza e posti ancora disponibili (req. 8). I posti
 * vengono dal registro della FASE 2; per i giorni già puliti dalla conservazione si sommano le statistiche anonime.
 */
@Service
public class OperationsService {

    /** Una prenotazione confermata di oggi: solo i dati che servono in sala (niente telefono, email, note). */
    public record TodayItem(UUID id, String reference, LocalTime time, String name, int guests, Band band, String bandLabel,
            String event) {
    }

    public record TodayBand(Band band, String label, int reservations, int guests) {
    }

    public record Today(LocalDate date, Band band, List<TodayItem> items, List<TodayBand> bands, int reservations, int guests) {
    }

    /**
     * Riga del riepilogo. {@code capacity}: persone al massimo nello stesso momento (null = fascia non prenotabile
     * quel giorno); {@code available}: il gruppo più grande che si può ancora accogliere in almeno un orario della
     * fascia (0 se chiuso, pieno o riservato a un evento).
     *
     * <p>FIX 2: {@code bandCapacity} è il budget della fascia (capienza del locale), {@code bandConfirmed} le persone
     * confermate che lo consumano (anche completate e non presentate), {@code bandRemaining} quante ne restano;
     * {@code physicalAvailable} il gruppo più grande che sta fisicamente in un orario della fascia (posti contemporanei
     * ed eventi). {@code available} è il minimo dei due: non si mostrano mai come disponibili posti che non ci sono
     * davvero. {@code eventArrivals} quanti arrivi della fascia sono riservati a un evento su {@code arrivals}.
     */
    public record SummaryRow(LocalDate date, Band band, String label, boolean closed, int requests, int pending, int confirmed,
            int guests, Integer capacity, Integer available, boolean fromStatistics, Integer bandCapacity, int bandConfirmed,
            Integer bandRemaining, Integer physicalAvailable, int eventArrivals, int arrivals, boolean limitedByPhysical) {
    }

    public record Summary(LocalDate from, LocalDate to, List<SummaryRow> rows) {
    }

    private static final int MAX_DAYS = 62;

    private final JdbcClient jdbc;
    private final BookingCalendar calendar;
    private final CapacityLedger ledger;
    private final Clock clock;
    private final BandBudget budget;

    OperationsService(JdbcClient jdbc, BookingCalendar calendar, CapacityLedger ledger, Clock clock, BandBudget budget) {
        this.budget = budget;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.ledger = ledger;
        this.clock = clock;
    }

    // ---- calendario di oggi -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Today today(Band filter) {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        List<TodayItem> all = jdbc.sql("""
                SELECT r.id, r.public_ref, r.starts_at, r.customer_name, r.guest_count, w.name, e.title
                  FROM reservations r
                  LEFT JOIN service_windows w ON w.id = r.service_window_id
                  LEFT JOIN events e ON e.id = r.event_id
                 WHERE r.reservation_date = ? AND r.status = 'CONFIRMED'
                 ORDER BY r.starts_at, r.public_ref
                """).param(today).query((rs, n) -> {
                    LocalTime time = instant(rs, 3).atZone(BusinessTime.ZONE).toLocalTime();
                    Band band = Band.of(rs.getString(6), time);
                    return new TodayItem(rs.getObject(1, UUID.class), rs.getString(2), time, rs.getString(4), rs.getInt(5), band,
                            band.label(), rs.getString(7));
                }).list();
        Map<Band, int[]> totals = new EnumMap<>(Band.class);
        for (Band b : Band.values()) {
            totals.put(b, new int[2]);
        }
        all.forEach(i -> {
            totals.get(i.band())[0]++;
            totals.get(i.band())[1] += i.guests();
        });
        List<TodayItem> items = filter == null ? all : all.stream().filter(i -> i.band() == filter).toList();
        List<TodayBand> bands = totals.entrySet().stream()
                .map(e -> new TodayBand(e.getKey(), e.getKey().label(), e.getValue()[0], e.getValue()[1])).toList();
        return new Today(today, filter, items, bands, items.size(), items.stream().mapToInt(TodayItem::guests).sum());
    }

    // ---- riepilogo per giorno e fascia -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        return summary(from, to, null);
    }

    /**
     * Riepilogo di un periodo, eventualmente di una sola fascia (FIX-1: la vista settimanale ne mostra una alla volta).
     * Con una fascia scelta c'è una riga per ogni giorno del periodo, anche senza fascia prenotabile quel giorno.
     */
    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to, Band only) {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? start.plusDays(13) : to;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiErrors.invalid("to", "Scegli un periodo da 1 a " + MAX_DAYS + " giorni.");
        }
        Map<String, int[]> live = new HashMap<>(); // requests, pending, confirmed, guests
        jdbc.sql("""
                SELECT r.reservation_date, r.starts_at, r.status, r.guest_count, w.name
                  FROM reservations r LEFT JOIN service_windows w ON w.id = r.service_window_id
                 WHERE r.reservation_date BETWEEN ? AND ?
                """).params(start, end).query((rs, n) -> {
                    LocalDate date = rs.getObject(1, LocalDate.class);
                    Band band = Band.of(rs.getString(5), instant(rs, 2).atZone(BusinessTime.ZONE).toLocalTime());
                    int[] c = live.computeIfAbsent(date + "|" + band, k -> new int[4]);
                    String status = rs.getString(3);
                    c[0]++;
                    if (status.equals("PENDING")) {
                        c[1]++;
                    }
                    if (status.equals("CONFIRMED") || status.equals("COMPLETED") || status.equals("NO_SHOW")) {
                        c[2]++;
                        c[3] += rs.getInt(4);
                    }
                    return null;
                }).list();
        Map<String, int[]> stats = new HashMap<>(); // requests, confirmed, guests, capacity(-1 = null)
        jdbc.sql("""
                SELECT service_date, band, requests, confirmed, confirmed_covers, capacity_covers
                  FROM reservation_stats WHERE service_date BETWEEN ? AND ?
                """).params(start, end).query((rs, n) -> {
                    Integer cap = (Integer) rs.getObject(6);
                    stats.put(rs.getObject(1, LocalDate.class) + "|" + rs.getString(2),
                            new int[] {rs.getInt(3), rs.getInt(4), rs.getInt(5), cap == null ? -1 : cap});
                    return null;
                }).list();
        List<LocalDate> closedDays = calendar.closedDays(start, end);
        Map<BandBudget.Slot, Integer> usage = budget.usage(start, end);
        int venueCapacity = budget.capacity();
        List<SummaryRow> rows = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            LocalDate day = d;
            List<DiningStore.WindowRow> windows = calendar.windowsOn(day);
            for (Band band : Band.values()) {
                if (only != null && band != only) {
                    continue;
                }
                String key = day + "|" + band;
                int[] l = live.getOrDefault(key, new int[4]);
                int[] s = stats.get(key);
                List<DiningStore.WindowRow> of = windows.stream().filter(w -> Band.of(w.name(), w.firstStart()) == band).toList();
                Integer capacity = null;
                Integer available = null;
                Integer physical = null;
                Integer bandCapacity = null;
                Integer bandRemaining = null;
                int bandConfirmed = usage.getOrDefault(new BandBudget.Slot(day, band), 0);
                int eventArrivals = 0;
                int arrivals = 0;
                boolean closed = closedDays.contains(day) || (!of.isEmpty() && of.stream().allMatch(w -> calendar.closed(day, w.id())));
                if (!of.isEmpty()) {
                    int[] ca = capacityAndAvailable(day, of);
                    capacity = ca[0];
                    eventArrivals = ca[2];
                    arrivals = ca[3];
                    bandCapacity = venueCapacity;
                    bandRemaining = Math.max(0, venueCapacity - bandConfirmed);
                    physical = closed || day.isBefore(today) ? 0 : ca[1];
                    available = Math.min(physical, bandRemaining);
                } else if (s != null && s[3] >= 0) {
                    capacity = s[3];
                }
                if (of.isEmpty() && l[0] == 0 && s == null && only == null) {
                    continue; // fascia che quel giorno non esiste e senza dati
                }
                rows.add(new SummaryRow(day, band, band.label(), closed, l[0] + (s == null ? 0 : s[0]), l[1],
                        l[2] + (s == null ? 0 : s[1]), l[3] + (s == null ? 0 : s[2]), capacity, available, s != null,
                        bandCapacity, bandConfirmed, bandRemaining, physical, eventArrivals, arrivals,
                        physical != null && bandRemaining != null && physical < bandRemaining));
            }
        }
        return new Summary(start, end, rows);
    }

    /**
     * Capienza (minima negli arrivi), gruppo più grande ancora accoglibile, arrivi riservati a un evento e arrivi
     * totali, dai quanti del registro.
     */
    private int[] capacityAndAvailable(LocalDate day, List<DiningStore.WindowRow> windows) {
        int capacity = Integer.MAX_VALUE;
        int available = 0;
        int eventArrivals = 0;
        int arrivals = 0;
        for (DiningStore.WindowRow w : windows) {
            Instant first = BusinessTime.toInstant(day, w.firstStart());
            Instant last = BusinessTime.toInstant(day, w.lastStart());
            Duration stay = Duration.ofMinutes(w.defaultDurationMinutes());
            List<CapacityLedger.QuantumState> quanta = ledger.read(w.zoneId(), first, last.plus(stay));
            Map<Instant, CapacityLedger.QuantumState> byStart = new HashMap<>();
            quanta.forEach(q -> byStart.put(q.slotStart(), q));
            for (Instant t = first; !t.isAfter(last); t = t.plus(Duration.ofMinutes(w.slotMinutes()))) {
                CapacityLedger.QuantumState at = byStart.get(t);
                if (at != null) {
                    capacity = Math.min(capacity, at.capacityCovers());
                }
                int free = Integer.MAX_VALUE;
                boolean event = false;
                for (Instant q = t; q.isBefore(t.plus(stay)); q = q.plus(Duration.ofMinutes(15))) {
                    CapacityLedger.QuantumState state = byStart.get(q);
                    event |= state != null && state.exclusiveEventId() != null;
                    free = Math.min(free, state == null ? 0 : state.exclusiveEventId() != null ? 0 : state.free());
                }
                arrivals++;
                eventArrivals += event ? 1 : 0;
                available = Math.max(available, free == Integer.MAX_VALUE ? 0 : free);
            }
        }
        return new int[] {capacity == Integer.MAX_VALUE ? 0 : capacity, available, eventArrivals, arrivals};
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
