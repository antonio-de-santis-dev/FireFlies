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

    /** Fascia di una zona che contiene l'orario (per gli inserimenti manuali), oppure la prima del giorno. */
    public Optional<DiningStore.WindowRow> windowFor(UUID zoneId, LocalDate date, LocalTime time) {
        List<DiningStore.WindowRow> windows = store.windowsOfZone(zoneId).stream().filter(w -> w.validOn(date)).toList();
        return windows.stream().filter(w -> w.covers(time)).findFirst().or(() -> windows.stream().findFirst());
    }

    /** Chiusura agli arrivi per quella data: della fascia o di tutto il giorno. */
    public boolean closed(LocalDate date, UUID windowId) {
        return store.exceptionsOn(date).stream()
                .anyMatch(e -> "CLOSED".equals(e.kind()) && (e.serviceWindowId() == null || e.serviceWindowId().equals(windowId)));
    }

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
