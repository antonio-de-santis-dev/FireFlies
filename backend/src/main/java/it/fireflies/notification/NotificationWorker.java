package it.fireflies.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker dell'outbox: un giro ogni {@code fireflies.notifications.worker.interval}. Acceso solo se
 * {@code fireflies.notifications.worker.enabled=true} (profilo {@code local}): in produzione resta spento finché non
 * c'è un provider reale autorizzato, così le notifiche restano «preparate, non inviate» invece di risultare simulate.
 */
@Component
class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationDispatcher dispatcher;
    private final boolean enabled;

    NotificationWorker(NotificationDispatcher dispatcher, @Value("${fireflies.notifications.worker.enabled:false}") boolean enabled,
            org.springframework.core.env.Environment environment) {
        if (enabled && java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            // in produzione esistono solo gateway finti: i clienti risulterebbero avvisati senza esserlo
            throw new IllegalStateException("Worker delle notifiche acceso in prod senza un provider reale autorizzato: "
                    + "lascia fireflies.notifications.worker.enabled=false.");
        }
        this.dispatcher = dispatcher;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${fireflies.notifications.worker.interval:PT30S}",
            fixedDelayString = "${fireflies.notifications.worker.interval:PT30S}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            NotificationDispatcher.Round round = dispatcher.runOnce();
            if (!round.outcomes().isEmpty() || round.recovered() > 0 || round.expired() > 0) {
                log.info("Notifiche: {} (lease scaduti {}, scadute {})", round.outcomes(), round.recovered(), round.expired());
            }
        } catch (RuntimeException e) {
            log.warn("Giro del worker delle notifiche non riuscito, riprovo al prossimo: {}", e.getMessage());
        }
    }
}
