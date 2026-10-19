package it.fireflies.common;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.HttpStatus;

/**
 * Traduce le violazioni dei vincoli PostgreSQL (ultima difesa delle invarianti, DATA_MODEL §4)
 * in errori comprensibili. Il nome del vincolo non viene mai esposto al client.
 */
public final class ConstraintViolationTranslator {

    record Translation(HttpStatus status, String code, String title, String detail) {
    }

    private static final Map<String, Translation> KNOWN = Map.of(
            "ux_app_users_email", new Translation(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    "Email già usata", "Esiste già un account con questa email."),
            "ex_table_no_overlap", new Translation(HttpStatus.CONFLICT, "TABLE_CONFLICT",
                    "Tavolo già occupato", "Il tavolo è già assegnato a un'altra prenotazione in quell'orario."),
            "ux_res_web_duplicate", new Translation(HttpStatus.CONFLICT, "DUPLICATE_REQUEST",
                    "Richiesta già inviata", "Esiste già una richiesta attiva con questo telefono per lo stesso orario."),
            "ck_slot_not_overbooked", new Translation(HttpStatus.CONFLICT, "CAPACITY_EXCEEDED",
                    "Posti esauriti", "Non ci sono abbastanza posti liberi in quell'orario."),
            "ck_event_quota", new Translation(HttpStatus.CONFLICT, "EVENT_QUOTA_EXCEEDED",
                    "Posti dell'evento esauriti", "La quota di posti dell'evento è esaurita."),
            "ck_override_complete", new Translation(HttpStatus.UNPROCESSABLE_CONTENT, "OVERRIDE_REASON_REQUIRED",
                    "Motivo obbligatorio", "L'eccezione sui posti del tavolo richiede autore e motivo."));

    /** Chiavi esterne verso le foto: cancellare una foto in uso è vietato dal database. */
    private static final Set<String> MEDIA_FOREIGN_KEYS = Set.of(
            "fk_content_hero_media", "fk_slide_media", "fk_owner_front_media", "fk_owner_back_media",
            "fk_staff_media", "fk_item_photo", "fk_event_poster", "fk_event_media_media");

    private static final Translation MEDIA_IN_USE = new Translation(HttpStatus.CONFLICT, "MEDIA_IN_USE",
            "Foto in uso", "La foto è usata nel sito: toglila prima dai contenuti che la usano.");

    private static final Translation GENERIC = new Translation(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
            "Dati non coerenti", "L'operazione non rispetta le regole dei dati e non è stata salvata.");

    private ConstraintViolationTranslator() {
    }

    static Translation translate(Throwable failure) {
        return constraintName(failure).map(ConstraintViolationTranslator::forConstraint).orElse(GENERIC);
    }

    static Translation forConstraint(String constraint) {
        if (MEDIA_FOREIGN_KEYS.contains(constraint)) {
            return MEDIA_IN_USE;
        }
        return KNOWN.getOrDefault(constraint, GENERIC);
    }

    /** Nome del vincolo violato, letto dal messaggio del server PostgreSQL nella catena delle cause. */
    public static Optional<String> constraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                if (message != null && message.getConstraint() != null) {
                    return Optional.of(message.getConstraint());
                }
            }
        }
        return Optional.empty();
    }
}
