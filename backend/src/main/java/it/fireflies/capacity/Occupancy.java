package it.fireflies.capacity;

import java.util.Objects;
import java.util.UUID;

/** Persone presenti in una zona per un intervallo: ciò che una prenotazione confermata occupa nel registro. */
public record Occupancy(UUID zoneId, OccupancyInterval interval, int covers) {

    public Occupancy {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(interval, "interval");
        if (covers < 1 || covers > 50) {
            throw new IllegalArgumentException("Persone fuori intervallo 1–50: " + covers);
        }
    }
}
