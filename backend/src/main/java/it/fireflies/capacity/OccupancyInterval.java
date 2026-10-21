package it.fireflies.capacity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;

import it.fireflies.common.ApiException;

/**
 * Intervallo di occupazione {@code [start, end)} allineato ai quanti da 15 minuti.
 * Gli istanti sono assoluti: un intervallo a cavallo del cambio d'ora ha la sua durata reale.
 */
public record OccupancyInterval(Instant start, Instant end) {

    public static final Duration QUANTUM = Duration.ofMinutes(15);
    /** Limite di sicurezza: nessuna occupazione (nemmeno di un evento) oltre 24 ore. */
    public static final Duration MAX_LENGTH = Duration.ofHours(24);

    public OccupancyInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!aligned(start) || !aligned(end)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ALIGNED_15_MIN", "Orario non valido",
                    "Orari e durate devono essere multipli di 15 minuti.",
                    Map.of("start", start.toString(), "end", end.toString()));
        }
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_LENGTH) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DURATION", "Durata non valida",
                    "La fine deve essere dopo l'inizio e la durata non può superare 24 ore.",
                    Map.of("start", start.toString(), "end", end.toString()));
        }
    }

    /**
     * FIX 2: il più piccolo intervallo di quanti interi che contiene {@code [start, end)}: inizio arrotondato al quanto
     * precedente, fine al successivo. Serve agli eventi a qualunque minuto (19:07–23:07 → 19:00–23:15): ogni quanto
     * toccato anche in parte è occupato o riservato, quindi ai confini non può passare una prenotazione sovrapposta.
     * Per orari già allineati è l'intervallo stesso.
     */
    public static OccupancyInterval covering(Instant start, Instant end) {
        long q = QUANTUM.toSeconds();
        long from = Math.floorDiv(start.getEpochSecond(), q) * q;
        long endSeconds = end.getEpochSecond() + (end.getNano() > 0 ? 1 : 0);
        long to = Math.floorDiv(endSeconds + q - 1, q) * q;
        return new OccupancyInterval(Instant.ofEpochSecond(from), Instant.ofEpochSecond(to));
    }

    public static OccupancyInterval of(Instant start, Duration length) {
        return new OccupancyInterval(start, start.plus(length));
    }

    /** Inizio di ogni quanto contenuto nell'intervallo, in ordine. */
    public List<Instant> quanta() {
        List<Instant> result = new ArrayList<>();
        for (Instant q = start; q.isBefore(end); q = q.plus(QUANTUM)) {
            result.add(q);
        }
        return result;
    }

    public boolean contains(Instant quantum) {
        return !quantum.isBefore(start) && quantum.isBefore(end);
    }

    public static boolean aligned(Instant instant) {
        return instant.getNano() == 0 && instant.getEpochSecond() % QUANTUM.toSeconds() == 0;
    }
}
