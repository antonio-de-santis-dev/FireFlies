package it.fireflies.dining;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.capacity.CapacityLedger;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;
import it.fireflies.common.BusinessTime;

/**
 * Configurazione delle prenotazioni (solo titolare, BOOKING_RULES §10): zone e capienze fisiche, aree e tavoli,
 * fasce prenotabili, regole generali, eccezioni di calendario. Ogni cambio che tocca una capienza ricalcola il
 * registro di occupazione nella stessa transazione: se i confermati non ci stanno più → {@code 409
 * CAPACITY_BELOW_CONFIRMED} e nulla cambia (§3.5).
 */
@Service
public class DiningService {

    public record Config(List<DiningStore.ZoneRow> zones, List<DiningStore.AreaRow> areas,
            List<DiningStore.TableRow> tables, List<DiningStore.WindowRow> windows, DiningStore.PolicyRow policy,
            List<DiningStore.ExceptionRow> exceptions) {
    }

    public record ZoneCommand(String name, int physicalCapacity, boolean active, int sortOrder) {
    }

    public record WindowCommand(UUID zoneId, int isoDay, String name, LocalTime firstStart, LocalTime lastStart,
            int slotMinutes, int defaultDurationMinutes, Integer maxCovers, LocalDate validFrom, LocalDate validTo,
            boolean active) {
    }

    public record PolicyCommand(boolean webBookingEnabled, int minLeadMinutes, int maxAdvanceDays, int maxPartySizeWeb,
            int pendingExpiryMinutesBeforeStart) {
    }

    private final DiningStore store;
    private final CapacityLedger ledger;
    private final AuditLog audit;
    private final Clock clock;

    DiningService(DiningStore store, CapacityLedger ledger, AuditLog audit, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Config config() {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        return new Config(store.zones(), store.areas(), store.tables(), store.windows(), store.policyOn(today).orElse(null),
                store.exceptionsFrom(today));
    }

    // --- zone ---------------------------------------------------------------------------------------------------

    @Transactional
    public DiningStore.ZoneRow createZone(UUID actor, String name) {
        UUID id = store.insertZone(requireText(name, "name", 40, "Dai un nome alla zona (massimo 40 caratteri)."), store.zones().size() + 1);
        audit(actor, "CAPACITY_ZONE", id, Map.of("created", true));
        return store.zone(id).orElseThrow();
    }

    @Transactional
    public DiningStore.ZoneRow updateZone(UUID actor, UUID id, int version, ZoneCommand c) {
        DiningStore.ZoneRow current = store.zone(id).orElseThrow(() -> ApiErrors.notFound("La zona non esiste."));
        if (current.version() != version) {
            throw ApiErrors.versionConflict();
        }
        String name = requireText(c.name(), "name", 40, "Dai un nome alla zona (massimo 40 caratteri).");
        if (c.active() && c.physicalCapacity() < 1) {
            throw ApiErrors.invalid("physicalCapacity", "Per attivare la zona indica una capienza di almeno 1 persona.");
        }
        Instant now = Instant.now(clock);
        if (current.active() && !c.active() && store.futureConfirmedInZone(id, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "ZONE_HAS_RESERVATIONS", "Zona con prenotazioni",
                    "Ci sono prenotazioni confermate future in questa zona: gestiscile prima di disattivarla.");
        }
        // capienza: stesso controllo dei confermati di ogni altro cambio (lock della zona, ricalcolo dei quanti futuri)
        ledger.changePhysicalCapacity(id, c.physicalCapacity(), now);
        store.updateZone(id, version + 1, name, c.active(), c.sortOrder());
        audit(actor, "CAPACITY_ZONE", id, Map.of("physicalCapacity", c.physicalCapacity(), "active", c.active()));
        return store.zone(id).orElseThrow();
    }

    // --- aree e tavoli ------------------------------------------------------------------------------------------

    @Transactional
    public DiningStore.AreaRow saveArea(UUID actor, UUID id, UUID zoneId, String name, boolean active, int sortOrder) {
        store.zone(zoneId).orElseThrow(() -> ApiErrors.invalid("zoneId", "Scegli una zona esistente."));
        String clean = requireText(name, "name", 60, "Dai un nome all'area (massimo 60 caratteri).");
        UUID areaId = id;
        if (id == null) {
            areaId = store.insertArea(zoneId, clean, sortOrder);
        } else if (!store.updateArea(id, zoneId, clean, active, sortOrder)) {
            throw ApiErrors.notFound("L'area non esiste.");
        }
        audit(actor, "DINING_AREA", areaId, Map.of("zoneId", zoneId));
        UUID finalId = areaId;
        return store.areas().stream().filter(a -> a.id().equals(finalId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteArea(UUID actor, UUID id) {
        if (store.areaHasTables(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "AREA_HAS_TABLES", "Area con tavoli",
                    "Nell'area ci sono tavoli: spostali o eliminali prima.");
        }
        if (!store.deleteArea(id)) {
            throw ApiErrors.notFound("L'area non esiste.");
        }
        audit(actor, "DINING_AREA", id, Map.of("deleted", true));
    }

    @Transactional
    public DiningStore.TableRow saveTable(UUID actor, UUID id, UUID areaId, String code, int minSeats, int maxSeats,
            boolean active, int sortOrder) {
        if (store.areas().stream().noneMatch(a -> a.id().equals(areaId))) {
            throw ApiErrors.invalid("areaId", "Scegli un'area esistente.");
        }
        String clean = requireText(code, "code", 10, "Indica il codice del tavolo (es. T4, massimo 10 caratteri).");
        if (minSeats < 1 || maxSeats < minSeats || maxSeats > 30) {
            throw ApiErrors.invalid("maxSeats", "Posti: minimo almeno 1, massimo tra il minimo e 30.");
        }
        UUID tableId = id;
        if (id == null) {
            tableId = store.insertTable(areaId, clean, minSeats, maxSeats, sortOrder);
        } else if (!store.updateTable(id, areaId, clean, minSeats, maxSeats, active, sortOrder)) {
            throw ApiErrors.notFound("Il tavolo non esiste.");
        }
        audit(actor, "DINING_TABLE", tableId, Map.of("maxSeats", maxSeats));
        UUID finalId = tableId;
        return store.tables().stream().filter(t -> t.id().equals(finalId)).findFirst().orElseThrow();
    }

    /** Un tavolo con storico di assegnazioni non si cancella: viene disattivato (resta nello storico). */
    @Transactional
    public boolean deleteTable(UUID actor, UUID id) {
        if (store.tables().stream().noneMatch(t -> t.id().equals(id))) {
            throw ApiErrors.notFound("Il tavolo non esiste.");
        }
        boolean history = store.tableHasHistory(id);
        if (history) {
            store.deactivateTable(id);
        } else {
            store.deleteTable(id);
        }
        audit(actor, "DINING_TABLE", id, Map.of(history ? "deactivated" : "deleted", true));
        return !history;
    }

    // --- fasce --------------------------------------------------------------------------------------------------

    @Transactional
    public DiningStore.WindowRow saveWindow(UUID actor, UUID id, WindowCommand c) {
        DiningStore.WindowRow previous = id == null ? null : store.window(id).orElseThrow(() -> ApiErrors.notFound("La fascia non esiste."));
        store.zone(c.zoneId()).orElseThrow(() -> ApiErrors.invalid("zoneId", "Scegli una zona esistente."));
        validate(c);
        LocalDate validFrom = c.validFrom() == null ? BusinessTime.serviceDate(Instant.now(clock)) : c.validFrom();
        DiningStore.WindowRow row = new DiningStore.WindowRow(id, c.zoneId(), c.isoDay(), c.name().strip(), c.firstStart(),
                c.lastStart(), c.slotMinutes(), c.defaultDurationMinutes(), c.maxCovers(), validFrom, c.validTo(), c.active());
        UUID windowId = id;
        if (id == null) {
            windowId = store.insertWindow(row);
        } else {
            store.updateWindow(row);
        }
        recompute(c.zoneId(), previous == null ? null : previous.zoneId());
        audit(actor, "SERVICE_WINDOW", windowId, Map.of("isoDay", c.isoDay(), "maxCovers", c.maxCovers() == null ? "null" : c.maxCovers()));
        return store.window(windowId).orElseThrow();
    }

    /** Una fascia già usata da prenotazioni non si cancella: viene disattivata. */
    @Transactional
    public boolean deleteWindow(UUID actor, UUID id) {
        DiningStore.WindowRow w = store.window(id).orElseThrow(() -> ApiErrors.notFound("La fascia non esiste."));
        boolean used = store.windowHasReservations(id);
        if (used) {
            store.updateWindow(new DiningStore.WindowRow(w.id(), w.zoneId(), w.isoDay(), w.name(), w.firstStart(), w.lastStart(),
                    w.slotMinutes(), w.defaultDurationMinutes(), w.maxCovers(), w.validFrom(), w.validTo(), false));
        } else {
            store.deleteWindow(id);
        }
        recompute(w.zoneId(), null);
        audit(actor, "SERVICE_WINDOW", id, Map.of(used ? "deactivated" : "deleted", true));
        return !used;
    }

    private static void validate(WindowCommand c) {
        if (c.isoDay() < 1 || c.isoDay() > 7) {
            throw ApiErrors.invalid("isoDay", "Scegli il giorno della settimana.");
        }
        requireText(c.name(), "name", 40, "Dai un nome alla fascia (es. Aperitivo, massimo 40 caratteri).");
        if (c.firstStart() == null || c.lastStart() == null || c.lastStart().isBefore(c.firstStart())) {
            throw ApiErrors.invalid("lastStart", "L'ultimo arrivo non può essere prima del primo.");
        }
        if (!aligned(c.firstStart()) || !aligned(c.lastStart())) {
            throw ApiErrors.invalid("firstStart", "Gli orari devono essere multipli di 15 minuti (es. 18:00, 18:15).");
        }
        if (c.slotMinutes() != 15 && c.slotMinutes() != 30 && c.slotMinutes() != 60) {
            throw ApiErrors.invalid("slotMinutes", "Il passo tra gli arrivi può essere 15, 30 o 60 minuti.");
        }
        if (c.defaultDurationMinutes() < 30 || c.defaultDurationMinutes() > 360 || c.defaultDurationMinutes() % 15 != 0) {
            throw ApiErrors.invalid("defaultDurationMinutes", "La durata va da 30 minuti a 6 ore, a passi di 15 minuti.");
        }
        if (c.lastStart().toSecondOfDay() / 60 + c.defaultDurationMinutes() > 24 * 60) {
            throw ApiErrors.invalid("lastStart", "In V1 le fasce non superano la mezzanotte: anticipa l'ultimo arrivo o riduci la durata.");
        }
        if (c.maxCovers() != null && c.maxCovers() < 0) {
            throw ApiErrors.invalid("maxCovers", "Il limite di presenze non può essere negativo.");
        }
        if (c.validTo() != null && c.validFrom() != null && c.validTo().isBefore(c.validFrom())) {
            throw ApiErrors.invalid("validTo", "La fine della validità non può essere prima dell'inizio.");
        }
    }

    // --- regole generali ----------------------------------------------------------------------------------------

    @Transactional
    public DiningStore.PolicyRow updatePolicy(UUID actor, PolicyCommand c) {
        if (c.minLeadMinutes() < 0 || c.minLeadMinutes() > 10080) {
            throw ApiErrors.invalid("minLeadMinutes", "L'anticipo minimo va da 0 minuti a 7 giorni.");
        }
        if (c.maxAdvanceDays() < 1 || c.maxAdvanceDays() > 365) {
            throw ApiErrors.invalid("maxAdvanceDays", "Si può prenotare da 1 a 365 giorni prima.");
        }
        if (c.maxPartySizeWeb() < 1 || c.maxPartySizeWeb() > 50) {
            throw ApiErrors.invalid("maxPartySizeWeb", "Il massimo di persone dal sito va da 1 a 50.");
        }
        if (c.pendingExpiryMinutesBeforeStart() < 0 || c.pendingExpiryMinutesBeforeStart() > 1440) {
            throw ApiErrors.invalid("pendingExpiryMinutesBeforeStart", "La scadenza va da 0 a 24 ore prima dell'arrivo.");
        }
        if (c.webBookingEnabled() && store.zones().stream().noneMatch(DiningStore.ZoneRow::active)) {
            throw ApiErrors.invalid("webBookingEnabled", "Prima di aprire le richieste dal sito attiva almeno una zona con la sua capienza.");
        }
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        store.upsertPolicy(today, c.webBookingEnabled(), c.minLeadMinutes(), c.maxAdvanceDays(), c.maxPartySizeWeb(),
                c.pendingExpiryMinutesBeforeStart(), actor);
        audit(actor, "BOOKING_POLICY", null, Map.of("webBookingEnabled", c.webBookingEnabled()));
        return store.policyOn(today).orElseThrow();
    }

    // --- eccezioni ----------------------------------------------------------------------------------------------

    @Transactional
    public DiningStore.ExceptionRow addException(UUID actor, LocalDate date, UUID windowId, String kind, Integer override, String reason) {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        if (date == null || date.isBefore(today)) {
            throw ApiErrors.invalid("serviceDate", "Scegli una data da oggi in poi.");
        }
        if (!"CLOSED".equals(kind) && !"CAPACITY_OVERRIDE".equals(kind)) {
            throw ApiErrors.invalid("kind", "Scegli «chiuso agli arrivi» oppure «limite di presenze».");
        }
        if ("CAPACITY_OVERRIDE".equals(kind) && (override == null || override < 0)) {
            throw ApiErrors.invalid("capacityOverride", "Indica il limite di presenze per quella data.");
        }
        DiningStore.WindowRow window = windowId == null ? null
                : store.window(windowId).orElseThrow(() -> ApiErrors.invalid("serviceWindowId", "La fascia scelta non esiste."));
        if (store.exceptionsOn(date).stream().anyMatch(e -> java.util.Objects.equals(e.serviceWindowId(), windowId))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXCEPTION_EXISTS", "Eccezione già presente",
                    "Per questa data e fascia c'è già un'eccezione: eliminala prima di crearne un'altra.");
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (cleanReason != null && cleanReason.length() > 140) {
            throw ApiErrors.invalid("reason", "Motivo troppo lungo (massimo 140 caratteri).");
        }
        UUID id = store.insertException(date, windowId, kind, "CAPACITY_OVERRIDE".equals(kind) ? override : null, cleanReason, actor);
        if ("CAPACITY_OVERRIDE".equals(kind)) {
            recomputeDay(date, window == null ? null : window.zoneId());
        }
        audit(actor, "BOOKING_EXCEPTION", id, Map.of("kind", kind, "date", date.toString()));
        return store.exception(id).orElseThrow();
    }

    @Transactional
    public void deleteException(UUID actor, UUID id) {
        DiningStore.ExceptionRow e = store.exception(id).orElseThrow(() -> ApiErrors.notFound("L'eccezione non esiste."));
        store.deleteException(id);
        if ("CAPACITY_OVERRIDE".equals(e.kind())) {
            UUID zone = e.serviceWindowId() == null ? null : store.window(e.serviceWindowId()).map(DiningStore.WindowRow::zoneId).orElse(null);
            recomputeDay(e.serviceDate(), zone);
        }
        audit(actor, "BOOKING_EXCEPTION", id, Map.of("deleted", true));
    }

    private void recomputeDay(LocalDate date, UUID zoneId) {
        Instant from = BusinessTime.toInstant(date, LocalTime.MIDNIGHT);
        Instant now = Instant.now(clock);
        Instant start = from.isAfter(now) ? from : now;
        for (DiningStore.ZoneRow z : store.zones()) {
            if (zoneId == null || z.id().equals(zoneId)) {
                ledger.recomputeCapacities(z.id(), start);
            }
        }
    }

    private void recompute(UUID zoneId, UUID previousZoneId) {
        Set<UUID> zones = new HashSet<>();
        zones.add(zoneId);
        if (previousZoneId != null) {
            zones.add(previousZoneId);
        }
        // ordine fisso delle zone: due cambi concorrenti non si bloccano a vicenda
        zones.stream().sorted().forEach(z -> ledger.recomputeCapacities(z, Instant.now(clock)));
    }

    private void audit(UUID actor, String type, UUID id, Map<String, ?> metadata) {
        audit.record(actor, AuditAction.BOOKING_CONFIG_UPDATED, type, id == null ? "-" : id.toString(), metadata);
    }

    private static boolean aligned(LocalTime t) {
        return t.getSecond() == 0 && t.getNano() == 0 && t.getMinute() % 15 == 0;
    }

    private static String requireText(String value, String field, int max, String message) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw ApiErrors.invalid(field, message);
        }
        return v;
    }
}
