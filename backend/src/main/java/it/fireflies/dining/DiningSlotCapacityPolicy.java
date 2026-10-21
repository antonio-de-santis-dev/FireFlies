package it.fireflies.dining;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import it.fireflies.capacity.SlotCapacityPolicy;

/**
 * Capienza effettiva di un quanto (BOOKING_RULES §3.2, FIX-1): <b>una sola fonte</b>, la capienza massima del locale
 * ({@code capacity_zones.physical_capacity}), uguale in ogni giorno e fascia. Non esistono più limiti per giorno,
 * fascia o data che la riducano (V12): l'occupazione dipende solo dalle prenotazioni confermate e dagli eventi.
 * Giorni e fasce chiusi non toccano la capienza: bloccano gli arrivi ({@link BookingCalendar#closed}).
 */
@Component
class DiningSlotCapacityPolicy implements SlotCapacityPolicy {

    @Override
    public int capacityFor(UUID zoneId, int physicalCapacity, Instant slotStart) {
        return Math.max(0, physicalCapacity);
    }
}
