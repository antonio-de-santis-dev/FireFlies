package it.fireflies.dining;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Lettura della configurazione per il modulo prenotazioni: regole generali, fasce di un giorno, chiusure, zone e
 * tavoli. Nessuna scrittura: la configurazione la cambia solo il titolare ({@link DiningService}).
 */
@Service
public class BookingCalendar {

    private final DiningStore store;

    BookingCalendar(DiningStore store) {
        this.store = store;
    }

    public Optional<DiningStore.PolicyRow> policy(LocalDate today) {
        return store.policyOn(today);
    }

    /** Fasce attive e valide in una data, di zone attive, in ordine di orario. */
    public List<DiningStore.WindowRow> windowsOn(LocalDate date) {
        List<UUID> active = activeZones().stream().map(DiningStore.ZoneRow::id).toList();
        return store.windows().stream().filter(w -> w.validOn(date) && active.contains(w.zoneId())).toList();
    }

    public Optional<DiningStore.WindowRow> window(UUID id) {
        return store.window(id);
    }

    /**
     * Fascia di una zona per un orario (inserimenti manuali): prima quella in cui l'orario è un arrivo (21:00 → «Sera»
     * anche se l'«Aperitivo» delle 20:30 copre ancora le 21:00), poi quella che lo copre, oppure la prima del giorno.
     */
    public Optional<DiningStore.WindowRow> windowFor(UUID zoneId, LocalDate date, LocalTime time) {
        List<DiningStore.WindowRow> windows = store.windowsOfZone(zoneId).stream().filter(w -> w.validOn(date)).toList();
        return windows.stream().filter(w -> !time.isBefore(w.firstStart()) && !time.isAfter(w.lastStart())).findFirst()
                .or(() -> windows.stream().filter(w -> w.covers(time)).findFirst())
                .or(() -> windows.stream().findFirst());
    }

    /** Chiusura agli arrivi per quella data: della fascia o di tutto il giorno, oppure locale chiuso quel giorno. */
    public boolean closed(LocalDate date, UUID windowId) {
        return !venueOpen(date) || store.exceptionsOn(date).stream()
                .anyMatch(e -> "CLOSED".equals(e.kind()) && (e.serviceWindowId() == null || e.serviceWindowId().equals(windowId)));
    }

    /**
     * Il locale è aperto in quella data (FASE 7, req. 10): una data straordinaria decide (chiusura o apertura), altrimenti
     * il giorno della settimana negli orari del sito. Un giorno chiuso non accetta richieste dal sito.
     */
    public boolean venueOpen(LocalDate date) {
        return store.openingException(date).map(e -> !e.closed())
                .orElseGet(() -> !store.weeklyClosed(date.getDayOfWeek().getValue()));
    }

    /**
     * Perché un giorno non accetta arrivi dal sito (FIX 2, audit del calendario): sono tre casi diversi e il gestore
     * deve poterli distinguere — riaprire un giorno negli orari settimanali non basta se quel giorno non ha fasce.
     */
    public enum DayClosure {
        /** Locale chiuso: orario settimanale o chiusura straordinaria. */
        VENUE_CLOSED,
        /** Locale aperto, ma nessuna fascia prenotabile configurata per quel giorno della settimana. */
        NO_SERVICE_WINDOWS,
        /** Ci sono fasce, ma sono tutte chiuse agli arrivi in quella data (chiusura di fascia o del giorno). */
        ARRIVALS_CLOSED
    }

    /**
     * Giorni dell'intervallo senza arrivi possibili dal sito: locale chiuso, chiusura di tutto il giorno o di ogni
     * fascia, nessuna fascia. Poche query in tutto (anche su 365 giorni), stessa logica di {@link #closed}.
     */
    public List<LocalDate> closedDays(LocalDate from, LocalDate to) {
        return List.copyOf(unavailableDays(from, to).keySet());
    }

    /** Come {@link #closedDays}, con il motivo di ogni giorno (in ordine di data). */
    public java.util.SortedMap<LocalDate, DayClosure> unavailableDays(LocalDate from, LocalDate to) {
        List<UUID> active = activeZones().stream().map(DiningStore.ZoneRow::id).toList();
        List<DiningStore.WindowRow> windows = store.windows().stream().filter(w -> active.contains(w.zoneId())).toList();
        java.util.Map<LocalDate, Boolean> special = new java.util.HashMap<>();
        // FIX-1: una data straordinaria può coprire un intervallo di giorni
        for (DiningStore.OpeningExceptionRow e : store.openingExceptionsBetween(from, to)) {
            for (LocalDate d = e.day().isBefore(from) ? from : e.day(); !d.isAfter(e.endDay()) && !d.isAfter(to); d = d.plusDays(1)) {
                special.put(d, !e.closed());
            }
        }
        boolean[] weeklyClosed = new boolean[8];
        for (int d = 1; d <= 7; d++) {
            weeklyClosed[d] = store.weeklyClosed(d);
        }
        java.util.Map<LocalDate, List<DiningStore.ExceptionRow>> exceptions = store.exceptionsFrom(from).stream()
                .filter(e -> "CLOSED".equals(e.kind()))
                .collect(java.util.stream.Collectors.groupingBy(DiningStore.ExceptionRow::serviceDate));
        java.util.SortedMap<LocalDate, DayClosure> closed = new java.util.TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate day = d;
            boolean open = special.getOrDefault(day, !weeklyClosed[day.getDayOfWeek().getValue()]);
            List<DiningStore.ExceptionRow> closures = exceptions.getOrDefault(day, List.of());
            List<DiningStore.WindowRow> ofDay = windows.stream().filter(w -> w.validOn(day)).toList();
            boolean anyArrival = ofDay.stream().anyMatch(w -> closures.stream()
                    .noneMatch(e -> e.serviceWindowId() == null || e.serviceWindowId().equals(w.id())));
            if (!open) {
                closed.put(day, DayClosure.VENUE_CLOSED);
            } else if (ofDay.isEmpty()) {
                closed.put(day, DayClosure.NO_SERVICE_WINDOWS);
            } else if (!anyArrival) {
                closed.put(day, DayClosure.ARRIVALS_CLOSED);
            }
        }
        return closed;
    }

    /** Capienza massima del locale (FIX-1): persone presenti al massimo, la stessa in ogni giorno e fascia. */
    public int venueCapacity() {
        return activeZones().stream().mapToInt(DiningStore.ZoneRow::physicalCapacity).sum();
    }

    /**
     * Persone al massimo per una richiesta dal sito (FIX-1): la capienza del locale, ma mai oltre {@link #WEB_PARTY_CEILING}.
     * Non è un'impostazione: è un limite di sicurezza fisso (una sola richiesta automatica non può bloccare mezzo locale)
     * e non rivela al pubblico la capienza vera. I gruppi più grandi passano da «Organizza un evento o un compleanno».
     */
    public int webPartyLimit() {
        return Math.min(venueCapacity(), WEB_PARTY_CEILING);
    }

    /** Tetto fisso delle persone per una richiesta dal sito (BOOKING_RULES §5, FIX-1). */
    public static final int WEB_PARTY_CEILING = 30;

    public List<DiningStore.ZoneRow> activeZones() {
        return store.zones().stream().filter(DiningStore.ZoneRow::active).toList();
    }

    public Optional<DiningStore.ZoneRow> zone(UUID id) {
        return store.zone(id);
    }

    public List<DiningStore.TableRow> tables(Collection<UUID> ids) {
        return store.tables().stream().filter(t -> ids.contains(t.id())).toList();
    }
}
