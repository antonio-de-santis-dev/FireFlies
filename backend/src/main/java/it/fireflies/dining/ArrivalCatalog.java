package it.fireflies.dining;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.BusinessTime;

/**
 * Catalogo interno delle fasce (FIX 2, branch fasceorarie). Le righe di {@code service_windows} restano: ogni
 * prenotazione ordinaria vi fa riferimento ({@code fk_res_window}, {@code ck_res_target}) e la sua fascia storica ne
 * deriva il nome ({@code fireflies_band}). Non le crea più il gestore: per ogni zona attiva e ogni giorno della
 * settimana che non ha fasce attive, il sistema inserisce quelle di {@link ArrivalRules#STANDARD}.
 *
 * <p>Solo inserimenti, mai modifiche o cancellazioni: i giorni che hanno già fasce (anche diverse da quelle standard)
 * restano com'erano, le prenotazioni esistenti non cambiano. Idempotente e serializzato da un lock consultivo: si può
 * chiamare all'avvio, a ogni salvataggio degli orari e all'attivazione della capienza.
 */
@Service
public class ArrivalCatalog {

    private static final Logger log = LoggerFactory.getLogger(ArrivalCatalog.class);
    private static final long LOCK_KEY = 0x46_46_41_52_52_49_56L; // «FFARRIV»

    private final JdbcClient jdbc;
    private final DiningStore store;
    private final AuditLog audit;
    private final Clock clock;

    ArrivalCatalog(JdbcClient jdbc, DiningStore store, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** Fasce standard per i giorni senza fasce attive delle zone attive. Restituisce quante righe ha inserito. */
    @Transactional
    public int ensureStandardWindows() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK_KEY).query().singleRow();
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        List<DiningStore.WindowRow> windows = store.windows();
        int inserted = 0;
        for (DiningStore.ZoneRow zone : store.zones().stream().filter(DiningStore.ZoneRow::active).toList()) {
            Map<Integer, Long> activeByDay = windows.stream()
                    .filter(w -> w.zoneId().equals(zone.id()) && w.active() && (w.validTo() == null || !w.validTo().isBefore(today)))
                    .collect(Collectors.groupingBy(DiningStore.WindowRow::isoDay, Collectors.counting()));
            for (int day = 1; day <= 7; day++) {
                if (activeByDay.containsKey(day)) {
                    continue; // fasce già presenti (anche personalizzate): restano com'erano
                }
                for (ArrivalRules.Schedule s : ArrivalRules.STANDARD) {
                    store.insertWindow(new DiningStore.WindowRow(null, zone.id(), day, s.name(), s.firstArrival(), s.lastArrival(),
                            s.stepMinutes(), s.durationMinutes(), today, null, true));
                    inserted++;
                }
            }
        }
        if (inserted > 0) {
            Set<UUID> zones = store.zones().stream().filter(DiningStore.ZoneRow::active).map(DiningStore.ZoneRow::id).collect(Collectors.toSet());
            audit.record(null, AuditAction.BOOKING_CONFIG_UPDATED, "SERVICE_WINDOW", "standard", Map.of("inserted", inserted, "zones", zones.size()));
            log.info("Fasce interne: inserite {} fasce standard nei giorni che non ne avevano", inserted);
        }
        return inserted;
    }

    /** All'avvio: i database creati prima della FASE 7 non avevano fasce nei giorni di chiusura (es. mercoledì). */
    @Service
    @Order(10)
    static class OnStartup implements ApplicationRunner {
        private final ArrivalCatalog catalog;

        OnStartup(ArrivalCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public void run(ApplicationArguments args) {
            catalog.ensureStandardWindows();
        }
    }
}
