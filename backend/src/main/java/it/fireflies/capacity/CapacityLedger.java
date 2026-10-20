package it.fireflies.capacity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.capacity.CapacityExceptions.CapacityBelowConfirmedException;
import it.fireflies.capacity.CapacityExceptions.CapacityExceededException;
import it.fireflies.capacity.CapacityExceptions.EventBlocksSlotException;
import it.fireflies.capacity.CapacityExceptions.SlotConflict;
import it.fireflies.capacity.CapacitySlotStore.SlotRow;
import it.fireflies.capacity.CapacitySlotStore.ZoneRow;
import it.fireflies.common.ApiException;

/**
 * Registro di occupazione (BOOKING_RULES §3). Ogni metodo partecipa alla transazione del chiamante
 * ({@code MANDATORY}): l'occupazione dei posti e il cambio di stato della prenotazione si salvano insieme o per niente.
 *
 * <p>Procedura: lock condiviso della zona → creazione dei quanti mancanti → lock dei quanti in ordine di orario →
 * controllo → aggiornamento. Il vincolo {@code ck_slot_not_overbooked} resta come ultima difesa.
 */
@Service
public class CapacityLedger {

    private final CapacitySlotStore store;
    private final SlotCapacityPolicy capacityPolicy;

    CapacityLedger(CapacitySlotStore store, SlotCapacityPolicy capacityPolicy) {
        this.store = store;
        this.capacityPolicy = capacityPolicy;
    }

    /** Occupa i posti di una prenotazione confermata. Errori: {@code CAPACITY_EXCEEDED}, {@code EVENT_BLOCKS_SLOT}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void occupy(Occupancy occupancy) {
        apply(List.of(), List.of(occupancy));
    }

    /**
     * Occupa i posti di una prenotazione confermata per un evento (BOOKING_RULES §7): stesse regole di
     * {@link #occupy}, ma i quanti riservati in esclusiva a <b>quell'</b>evento sono ammessi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void occupyForEvent(Occupancy occupancy, UUID eventId) {
        apply(List.of(), List.of(occupancy), eventId);
    }

    /** Come {@link #change}, per una prenotazione di un evento (es. persone cambiate). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeForEvent(Occupancy before, Occupancy after, UUID eventId) {
        apply(List.of(before), List.of(after), eventId);
    }

    /**
     * Riserva in esclusiva a un evento i quanti di una zona nell'intervallo (pubblicazione di un evento esclusivo,
     * §7.3). Stessa procedura a lock ordinati di una conferma: lock condiviso della zona, quanti mancanti creati,
     * quanti bloccati in ordine di orario. Se un quanto ha già persone confermate o è riservato a un altro evento,
     * <b>nulla viene marcato</b> e si restituiscono i quanti in conflitto (il chiamante annulla la transazione).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<QuantumState> markExclusive(UUID zoneId, UUID eventId, OccupancyInterval interval) {
        ZoneRow zone = store.lockZoneShared(zoneId).orElseThrow(() -> CapacityExceptions.zoneNotFound(zoneId));
        List<Instant> quanta = interval.quanta();
        store.insertMissingSlots(zoneId, quanta, q -> capacityPolicy.capacityFor(zoneId, zone.physicalCapacity(), q));
        List<SlotRow> locked = store.lockSlots(zoneId, interval.start(), interval.end());
        List<QuantumState> conflicts = locked.stream()
                .filter(s -> s.occupiedCovers() > 0 || (s.exclusiveEventId() != null && !s.exclusiveEventId().equals(eventId)))
                .map(s -> new QuantumState(s.slotStart(), s.capacityCovers(), s.occupiedCovers(), s.exclusiveEventId()))
                .toList();
        if (conflicts.isEmpty()) {
            store.setExclusive(zoneId, eventId, interval.start(), interval.end());
        }
        return conflicts;
    }

    /** Toglie la riserva esclusiva di un evento da tutti i quanti (bloccati prima in ordine di zona e orario). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int unmarkExclusive(UUID eventId) {
        store.lockExclusiveSlots(eventId);
        return store.clearExclusive(eventId);
    }

    /** Libera i posti (annullamento, oppure i soli quanti futuri per uscita anticipata e no-show). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Occupancy occupancy) {
        apply(List.of(occupancy), List.of());
    }

    /**
     * Modifica di una prenotazione confermata (orario, durata, persone, zona): libera il vecchio intervallo e occupa
     * il nuovo nella stessa operazione, controllando solo la differenza netta. Se il nuovo non sta, nulla cambia.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void change(Occupancy before, Occupancy after) {
        apply(List.of(before), List.of(after));
    }

    /**
     * Cambia la capienza fisica di una zona dai quanti che iniziano da {@code from} in poi.
     * Se in qualche quanto ci sono più persone confermate della nuova capienza → {@code CAPACITY_BELOW_CONFIRMED}:
     * nessuna prenotazione viene toccata (BOOKING_RULES §3.5).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changePhysicalCapacity(UUID zoneId, int newCapacity, Instant from) {
        if (newCapacity < 0 || newCapacity > 1000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Capienza non valida",
                    "La capienza deve essere tra 0 e 1000 persone.", Map.of("physicalCapacity", newCapacity));
        }
        store.lockZoneForUpdate(zoneId).orElseThrow(() -> CapacityExceptions.zoneNotFound(zoneId));
        recompute(zoneId, newCapacity, from);
        store.updatePhysicalCapacity(zoneId, newCapacity);
    }

    /**
     * Ricalcola la capienza effettiva dei quanti già esistenti da {@code from} in poi dopo un cambio di fasce o
     * eccezioni (la nuova configurazione è già scritta nella stessa transazione). Stessa regola dei cambi di capienza:
     * se i confermati superano il nuovo limite → {@code CAPACITY_BELOW_CONFIRMED} e la transazione si annulla.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeCapacities(UUID zoneId, Instant from) {
        ZoneRow zone = store.lockZoneForUpdate(zoneId).orElseThrow(() -> CapacityExceptions.zoneNotFound(zoneId));
        recompute(zoneId, zone.physicalCapacity(), from);
    }

    private void recompute(UUID zoneId, int physicalCapacity, Instant from) {
        Instant firstQuantum = floorToQuantum(from);
        List<SlotConflict> conflicts = new ArrayList<>();
        Map<Instant, Integer> newCapacities = new TreeMap<>();
        for (SlotRow slot : store.lockSlotsFrom(zoneId, firstQuantum)) {
            int capacity = capacityPolicy.capacityFor(zoneId, physicalCapacity, slot.slotStart());
            if (slot.occupiedCovers() > capacity) {
                conflicts.add(new SlotConflict(slot.slotStart(), slot.occupiedCovers(), capacity));
            }
            newCapacities.put(slot.slotStart(), capacity);
        }
        if (!conflicts.isEmpty()) {
            throw new CapacityBelowConfirmedException(zoneId, conflicts);
        }
        if (!newCapacities.isEmpty()) {
            store.updateCapacities(zoneId, newCapacities);
        }
    }

    /** Persone confermate e capienza effettiva per ogni quanto di un intervallo, senza lock (solo lettura). */
    @Transactional(readOnly = true)
    public List<QuantumState> read(UUID zoneId, Instant from, Instant to) {
        ZoneRow zone = store.zone(zoneId).orElseThrow(() -> CapacityExceptions.zoneNotFound(zoneId));
        Map<Instant, SlotRow> existing = store.readSlots(zoneId, from, to).stream()
                .collect(Collectors.toMap(SlotRow::slotStart, Function.identity()));
        List<QuantumState> result = new ArrayList<>();
        for (Instant q = floorToQuantum(from); q.isBefore(to); q = q.plus(OccupancyInterval.QUANTUM)) {
            SlotRow slot = existing.get(q);
            int capacity = slot != null ? slot.capacityCovers() : capacityPolicy.capacityFor(zoneId, zone.physicalCapacity(), q);
            result.add(new QuantumState(q, capacity, slot == null ? 0 : slot.occupiedCovers(),
                    slot == null ? null : slot.exclusiveEventId()));
        }
        return result;
    }

    /** Stato di un quanto: capienza effettiva, persone confermate presenti, eventuale evento esclusivo. */
    public record QuantumState(Instant slotStart, int capacityCovers, int occupiedCovers, UUID exclusiveEventId) {
        public int free() {
            return Math.max(0, capacityCovers - occupiedCovers);
        }
    }

    private void apply(List<Occupancy> removed, List<Occupancy> added) {
        apply(removed, added, null);
    }

    /** {@code eventId}: l'evento delle prenotazioni coinvolte, i cui quanti esclusivi sono ammessi (null = ordinaria). */
    private void apply(List<Occupancy> removed, List<Occupancy> added, UUID eventId) {
        SortedMap<UUID, SortedMap<Instant, Integer>> deltas = netDeltas(removed, added);
        for (Map.Entry<UUID, SortedMap<Instant, Integer>> zone : deltas.entrySet()) {
            applyToZone(zone.getKey(), zone.getValue(), eventId);
        }
    }

    private void applyToZone(UUID zoneId, SortedMap<Instant, Integer> deltas, UUID eventId) {
        ZoneRow zone = store.lockZoneShared(zoneId).orElseThrow(() -> CapacityExceptions.zoneNotFound(zoneId));
        List<Instant> growing = deltas.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
        if (!growing.isEmpty()) {
            if (!zone.active()) {
                throw CapacityExceptions.zoneInactive(zoneId);
            }
            store.insertMissingSlots(zoneId, growing,
                    q -> capacityPolicy.capacityFor(zoneId, zone.physicalCapacity(), q));
        }

        Instant from = deltas.firstKey();
        Instant to = deltas.lastKey().plus(OccupancyInterval.QUANTUM);
        Map<Instant, SlotRow> locked = store.lockSlots(zoneId, from, to).stream()
                .collect(Collectors.toMap(SlotRow::slotStart, Function.identity()));

        for (Map.Entry<Instant, Integer> entry : deltas.entrySet()) {
            Instant quantum = entry.getKey();
            int delta = entry.getValue();
            SlotRow slot = locked.get(quantum);
            if (delta > 0) {
                if (slot.exclusiveEventId() != null && !slot.exclusiveEventId().equals(eventId)) {
                    throw new EventBlocksSlotException(zoneId, quantum, slot.exclusiveEventId());
                }
                if (slot.occupiedCovers() + delta > slot.capacityCovers()) {
                    throw new CapacityExceededException(zoneId, quantum, slot.capacityCovers(), slot.occupiedCovers(), delta);
                }
            } else if (slot == null || slot.occupiedCovers() + delta < 0) {
                throw new IllegalStateException("Registro di occupazione incoerente: rilascio di " + (-delta)
                        + " persone dal quanto " + quantum + " della zona " + zoneId);
            }
        }
        store.addOccupied(zoneId, deltas);
    }

    /** Somma per zona e quanto: −persone per ciò che si libera, +persone per ciò che si occupa; le differenze nulle spariscono. */
    static SortedMap<UUID, SortedMap<Instant, Integer>> netDeltas(List<Occupancy> removed, List<Occupancy> added) {
        SortedMap<UUID, SortedMap<Instant, Integer>> deltas = new TreeMap<>();
        removed.forEach(o -> accumulate(deltas, o, -o.covers()));
        added.forEach(o -> accumulate(deltas, o, o.covers()));
        deltas.values().forEach(byQuantum -> byQuantum.values().removeIf(d -> d == 0));
        deltas.values().removeIf(Map::isEmpty);
        return deltas;
    }

    private static void accumulate(SortedMap<UUID, SortedMap<Instant, Integer>> deltas, Occupancy o, int amount) {
        SortedMap<Instant, Integer> byQuantum = deltas.computeIfAbsent(o.zoneId(), z -> new TreeMap<>());
        o.interval().quanta().forEach(q -> byQuantum.merge(q, amount, Integer::sum));
    }

    static Instant floorToQuantum(Instant instant) {
        long seconds = OccupancyInterval.QUANTUM.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), seconds) * seconds);
    }
}
