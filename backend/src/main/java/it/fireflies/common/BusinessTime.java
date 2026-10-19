package it.fireflies.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Fuso orario di esercizio del locale ({@code Europe/Rome}) e conversioni rigorose tra orario locale e istante.
 * PostgreSQL sposterebbe in silenzio un orario inesistente al cambio d'ora: il controllo va fatto qui (BOOKING_RULES §4).
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private BusinessTime() {
    }

    /**
     * Converte data e ora locali in un istante.
     * Orario inesistente (salto in avanti dell'ora legale) → {@code 422 INVALID_LOCAL_TIME};
     * orario ambiguo (ritorno all'ora solare) → primo offset, cioè l'ora legale.
     */
    public static Instant toInstant(LocalDate date, LocalTime time) {
        LocalDateTime local = LocalDateTime.of(date, time);
        if (ZONE.getRules().getValidOffsets(local).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_LOCAL_TIME", "Orario inesistente",
                    "Questo orario non esiste per il cambio dell'ora legale.",
                    Map.of("date", date.toString(), "time", time.toString()));
        }
        return ZonedDateTime.ofLocal(local, ZONE, null).toInstant();
    }

    /** Data locale del servizio per un istante. */
    public static LocalDate serviceDate(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }
}
