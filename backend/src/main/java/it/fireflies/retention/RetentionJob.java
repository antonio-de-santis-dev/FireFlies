package it.fireflies.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Avvio della pulizia notturna alle 02:00, 03:00, 04:00 e 05:00 ora di Roma ({@code zone} gestisce l'ora legale: il
 * giorno del salto in avanti le 02:00 non esistono e parte alle 03:00; il giorno del ritorno le 02:00 capitano due
 * volte). Ogni giorno vale una sola esecuzione riuscita ({@link RetentionService#runIfDue}): gli orari successivi
 * servono da nuovo tentativo se la prima non è andata a buon fine. Disattivabile con
 * {@code fireflies.retention.enabled=false} (nei test si invoca a mano).
 */
@Component
class RetentionJob {

    static final String CRON = "0 0 2-5 * * *";
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;
    private final boolean enabled;

    RetentionJob(RetentionService retention, @Value("${fireflies.retention.enabled:true}") boolean enabled) {
        this.retention = retention;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${fireflies.retention.cron:" + CRON + "}", zone = "Europe/Rome")
    void nightly() {
        if (!enabled) {
            return;
        }
        try {
            retention.runIfDue();
        } catch (RuntimeException e) {
            log.warn("Pulizia notturna rimandata al prossimo orario: {}", e.getMessage());
        }
    }
}
