package it.fireflies.capacity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import it.fireflies.common.ApiException;

/** Errori del registro di occupazione, con il contesto che serve alla UI per spiegare il conflitto. */
public final class CapacityExceptions {

    private CapacityExceptions() {
    }

    public static final class CapacityExceededException extends ApiException {
        CapacityExceededException(UUID zoneId, Instant firstFullSlot, int capacity, int occupied, int requested) {
            super(HttpStatus.CONFLICT, "CAPACITY_EXCEEDED", "Posti esauriti",
                    "In quell'orario restano " + Math.max(0, capacity - occupied) + " posti, la richiesta è per "
                            + requested + " persone.",
                    Map.of("zoneId", zoneId, "firstFullSlot", firstFullSlot.toString(), "capacityCovers", capacity,
                            "occupiedCovers", occupied, "remainingCovers", Math.max(0, capacity - occupied)));
        }
    }

    public static final class EventBlocksSlotException extends ApiException {
        EventBlocksSlotException(UUID zoneId, Instant slot, UUID eventId) {
            super(HttpStatus.CONFLICT, "EVENT_BLOCKS_SLOT", "Orario riservato a un evento",
                    "In quell'orario c'è un evento esclusivo: le prenotazioni ordinarie non sono possibili.",
                    Map.of("zoneId", zoneId, "slot", slot.toString(), "eventId", eventId));
        }
    }

    /** Un quanto in cui la nuova capienza sarebbe inferiore alle persone già confermate. */
    public record SlotConflict(Instant slotStart, int occupiedCovers, int newCapacity) {
    }

    public static final class CapacityBelowConfirmedException extends ApiException {
        private final List<SlotConflict> conflicts;

        CapacityBelowConfirmedException(UUID zoneId, List<SlotConflict> conflicts) {
            super(HttpStatus.CONFLICT, "CAPACITY_BELOW_CONFIRMED", "Capienza inferiore ai confermati",
                    "In " + conflicts.size() + " intervalli ci sono già più persone confermate della nuova capienza. "
                            + "Nessuna prenotazione è stata annullata: decidi tu come procedere.",
                    Map.of("zoneId", zoneId, "conflicts", conflicts));
            this.conflicts = List.copyOf(conflicts);
        }

        public List<SlotConflict> conflicts() {
            return conflicts;
        }
    }

    static ApiException zoneNotFound(UUID zoneId) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Zona non trovata",
                "La zona di capienza indicata non esiste.", Map.of("zoneId", zoneId));
    }

    static ApiException zoneInactive(UUID zoneId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ZONE_INACTIVE", "Zona non attiva",
                "La zona non è attiva: il titolare deve prima impostarne la capienza.", Map.of("zoneId", zoneId));
    }
}
