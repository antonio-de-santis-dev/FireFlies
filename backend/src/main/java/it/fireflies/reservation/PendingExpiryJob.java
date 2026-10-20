package it.fireflies.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fa scadere le richieste rimaste in attesa troppo vicino all'orario di arrivo (BOOKING_RULES §6). Più istanze
 * possono girare insieme: {@code FOR UPDATE SKIP LOCKED} impedisce che la stessa richiesta venga toccata due volte.
 */
@Component
class PendingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingExpiryJob.class);

    private final ReservationService reservations;

    PendingExpiryJob(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(initialDelayString = "${fireflies.reservations.expiry-sweep:PT5M}",
            fixedDelayString = "${fireflies.reservations.expiry-sweep:PT5M}")
    void run() {
        int expired = reservations.expirePending();
        if (expired > 0) {
            log.info("Richieste scadute senza risposta: {}", expired);
        }
    }
}
