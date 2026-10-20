package it.fireflies.dining;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import it.fireflies.capacity.SlotCapacityPolicy;
import it.fireflies.common.BusinessTime;

/**
 * Capienza effettiva di un quanto (BOOKING_RULES §3.2): il minimo tra la capienza fisica della zona e i limiti delle
 * fasce che contengono il quanto; un'eccezione «limite di presenze» per quella data sostituisce il limite della fascia
 * (o vale per tutto il giorno se non indica una fascia). Fuori da ogni fascia vale la capienza fisica.
 */
@Component
class DiningSlotCapacityPolicy implements SlotCapacityPolicy {

    private final DiningStore store;

    DiningSlotCapacityPolicy(DiningStore store) {
        this.store = store;
    }

    @Override
    public int capacityFor(UUID zoneId, int physicalCapacity, Instant slotStart) {
        ZonedDateTime local = slotStart.atZone(BusinessTime.ZONE);
        LocalDate date = local.toLocalDate();
        List<DiningStore.ExceptionRow> exceptions = store.exceptionsOn(date);
        int capacity = physicalCapacity;
        for (DiningStore.ExceptionRow e : exceptions) {
            if (e.serviceWindowId() == null && "CAPACITY_OVERRIDE".equals(e.kind())) {
                capacity = Math.min(capacity, e.capacityOverride());
            }
        }
        for (DiningStore.WindowRow w : store.windowsOfZone(zoneId)) {
            if (!w.validOn(date) || !w.covers(local.toLocalTime())) {
                continue;
            }
            Integer limit = exceptions.stream()
                    .filter(e -> w.id().equals(e.serviceWindowId()) && "CAPACITY_OVERRIDE".equals(e.kind()))
                    .map(DiningStore.ExceptionRow::capacityOverride).findFirst().orElse(w.maxCovers());
            if (limit != null) {
                capacity = Math.min(capacity, limit);
            }
        }
        return Math.max(0, capacity);
    }
}
