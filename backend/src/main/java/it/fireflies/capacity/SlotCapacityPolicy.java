package it.fireflies.capacity;

import java.time.Instant;
import java.util.UUID;

/**
 * Capienza effettiva di un quanto (BOOKING_RULES §3.2): {@code min(fisica, limite della fascia o dell'eccezione)}.
 * Implementata dal modulo {@code dining} ({@code DiningSlotCapacityPolicy}), che conosce fasce ed eccezioni.
 * Viene interrogata solo sotto lock della riga zona, quindi vede una configurazione stabile.
 */
public interface SlotCapacityPolicy {

    int capacityFor(UUID zoneId, int physicalCapacity, Instant slotStart);
}
