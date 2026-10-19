package it.fireflies.capacity;

import java.time.Instant;
import java.util.UUID;

/**
 * Capienza effettiva di un quanto (BOOKING_RULES §3.2).
 * In FASE 2 vale la capienza fisica della zona; in FASE 5 il modulo {@code dining} fornirà un'implementazione
 * che applica il limite della fascia e le eccezioni di calendario ({@code min(fisica, limite)}).
 * Viene interrogata solo sotto lock della riga zona, quindi vede una configurazione stabile.
 */
public interface SlotCapacityPolicy {

    int capacityFor(UUID zoneId, int physicalCapacity, Instant slotStart);
}
