package it.fireflies.notification;

/** Esito di una chiamata a un provider, classificato come in ADR-0005. */
public record SendResult(Outcome outcome, String providerMessageId, String errorCode) {

    public enum Outcome {
        /** Il provider ha accettato il messaggio (non significa ancora consegnato). */
        ACCEPTED,
        /** Rifiuto definitivo (destinatario non valido): nessun ritentativo. */
        REJECTED_PERMANENT,
        /** Errore sicuramente avvenuto prima dell'invio: si può ritentare. */
        TRANSIENT_ERROR,
        /** Non sappiamo se il messaggio è partito (timeout dopo l'invio): niente ritentativo cieco. */
        UNKNOWN
    }

    public static SendResult accepted(String providerMessageId) {
        return new SendResult(Outcome.ACCEPTED, providerMessageId, null);
    }
}
