package it.fireflies.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.common.BusinessTime;
import it.fireflies.dining.BookingCalendar;
import it.fireflies.dining.DiningStore;

/**
 * Orari prenotabili dal sito per un giorno (BOOKING_RULES §4): gli arrivi delle fasce aperte, ciascuno con un sì/no.
 * Al pubblico non si mostrano mai i numeri dei posti, solo se l'orario è richiedibile.
 */
@Service
public class BookingOptionsService {

    /**
     * {@code closedDates}: giorni tra {@code firstDate} e {@code lastDate} in cui dal sito non si può chiedere nulla
     * (locale chiuso per orario settimanale o data straordinaria, chiusura di tutto il giorno, nessuna fascia): il
     * calendario del sito li disabilita, il backend li rifiuta comunque ({@code DAY_CLOSED}).
     */
    public record Options(boolean webBookingEnabled, int maxPartySize, int maxAdvanceDays, int minLeadMinutes,
            LocalDate firstDate, LocalDate lastDate, LocalDate date, List<Group> groups, List<LocalDate> closedDates) {
    }

    public record Group(String name, List<Slot> slots) {
    }

    /** {@code reason}: null se richiedibile, altrimenti TOO_EARLY, DAY_CLOSED, FULL, EVENT. */
    public record Slot(LocalTime time, boolean available, String reason) {
    }

    private final BookingCalendar calendar;
    private final CapacityLedger ledger;
    private final Clock clock;

    BookingOptionsService(BookingCalendar calendar, CapacityLedger ledger, Clock clock) {
        this.calendar = calendar;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Options options(LocalDate date, Integer guests) {
        Instant now = Instant.now(clock);
        LocalDate today = BusinessTime.serviceDate(now);
        DiningStore.PolicyRow policy = calendar.policy(today).orElse(null);
        if (policy == null || !policy.webBookingEnabled()) {
            return new Options(false, 0, 0, 0, null, null, date, List.of(), List.of());
        }
        LocalDate last = today.plusDays(policy.maxAdvanceDays());
        List<LocalDate> closedDates = calendar.closedDays(today, last);
        if (date == null || date.isBefore(today) || date.isAfter(last)) {
            return new Options(true, calendar.webPartyLimit(), policy.maxAdvanceDays(), policy.minLeadMinutes(), today, last,
                    date, List.of(), closedDates);
        }
        int party = guests == null ? 1 : Math.max(1, guests);
        Instant earliest = now.plus(Duration.ofMinutes(policy.minLeadMinutes()));
        List<Group> groups = new ArrayList<>();
        for (DiningStore.WindowRow w : calendar.windowsOn(date)) {
            boolean closed = calendar.closed(date, w.id());
            List<Slot> slots = new ArrayList<>();
            for (LocalTime t = w.firstStart(); !t.isAfter(w.lastStart()); t = t.plusMinutes(w.slotMinutes())) {
                Instant start = BusinessTime.toInstant(date, t);
                String reason = null;
                if (start.isBefore(earliest)) {
                    reason = "TOO_EARLY";
                } else if (closed) {
                    reason = "DAY_CLOSED";
                } else {
                    for (CapacityLedger.QuantumState q : ledger.read(w.zoneId(), start, start.plus(Duration.ofMinutes(w.defaultDurationMinutes())))) {
                        if (q.exclusiveEventId() != null) {
                            reason = "EVENT";
                            break;
                        }
                        if (q.free() < party) {
                            reason = "FULL";
                        }
                    }
                }
                slots.add(new Slot(t, reason == null, reason));
                if (t.plusMinutes(w.slotMinutes()).isBefore(t)) {
                    break; // giro della mezzanotte: le fasce non la attraversano, ma per sicurezza
                }
            }
            groups.add(new Group(w.name(), slots));
        }
        return new Options(true, calendar.webPartyLimit(), policy.maxAdvanceDays(), policy.minLeadMinutes(), today, last, date,
                groups, closedDates);
    }
}
