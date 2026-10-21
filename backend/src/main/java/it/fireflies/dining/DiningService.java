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

    /**
     * {@code venueCapacity}: capienza massima del locale (FIX-1), la stessa in ogni giorno e fascia;
     * {@code openingExceptions}: chiusure e aperture straordinarie da oggi in poi.
     */
    public record Config(List<DiningStore.ZoneRow> zones, List<DiningStore.AreaRow> areas,
            List<DiningStore.TableRow> tables, List<DiningStore.WindowRow> windows, DiningStore.PolicyRow policy,
            List<DiningStore.ExceptionRow> exceptions, int venueCapacity, List<DiningStore.OpeningExceptionRow> openingExceptions) {
    }

    public record ZoneCommand(String name, int physicalCapacity, boolean active, int sortOrder) {
    }

    public record WindowCommand(UUID zoneId, int isoDay, String name, LocalTime firstStart, LocalTime lastStart,
            int slotMinutes, int defaultDurationMinutes, LocalDate validFrom, LocalDate validTo, boolean active) {
    }

    public record PolicyCommand(boolean webBookingEnabled, int minLeadMinutes, int maxAdvanceDays,
            int pendingExpiryMinutesBeforeStart) {
    }

    /** Chiusura ({@code closed}) o apertura straordinaria dal giorno {@code from} al giorno {@code to} compresi. */
    public record OpeningCommand(LocalDate from, LocalDate to, boolean closed, LocalTime opensAt, LocalTime closesAt, String note) {
    }

    /**
     * Esito del salvataggio di una data straordinaria: per una chiusura, i giorni dell'intervallo con prenotazioni già
     * in attesa o confermate (restano valide: si gestiscono a mano, mai cancellate da sole).
     */
    public record OpeningResult(DiningStore.OpeningExceptionRow exception, long activeReservations,
            List<DiningStore.DayReservations> conflicts) {
    }

    /** Lunghezza massima di un intervallo straordinario (un anno), uguale al vincolo della V12. */
    static final int MAX_OPENING_RANGE_DAYS = 366;

    /** Capienza massima del locale accettata dall'interfaccia (persone presenti nello stesso momento). */
    static final int MAX_VENUE_CAPACITY = 1000; // stesso limite del registro (CapacityLedger.changePhysicalCapacity)

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
        int venue = store.zones().stream().filter(DiningStore.ZoneRow::active).mapToInt(DiningStore.ZoneRow::physicalCapacity).sum();
        return new Config(store.zones(), store.areas(), store.tables(), store.windows(), store.policyOn(today).orElse(null),
                store.exceptionsFrom(today), venue, store.openingExceptionsBetween(today, today.plusYears(2)));
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

    /**
     * «Capienza massima del locale» (FIX-1, unica fonte della capienza): la capienza fisica dell'unica zona attiva.
     * Stessi controlli di ogni cambio di capienza (lock della zona, ricalcolo dei quanti futuri): se in qualche quarto
     * d'ora ci sono già più persone confermate → {@code 409 CAPACITY_BELOW_CONFIRMED} con data, ora e persone, e nulla
     * cambia. Con più zone attive (configurazione non più gestita dall'interfaccia) → {@code 409 MULTIPLE_ZONES}.
     */
    @Transactional
    public DiningStore.ZoneRow setVenueCapacity(UUID actor, int capacity) {
        if (capacity < 1 || capacity > MAX_VENUE_CAPACITY) {
            throw ApiErrors.invalid("capacity", "Indica la capienza del locale: da 1 a " + MAX_VENUE_CAPACITY + " persone.");
        }
        List<DiningStore.ZoneRow> zones = store.zones();
        List<DiningStore.ZoneRow> active = zones.stream().filter(DiningStore.ZoneRow::active).toList();
        if (active.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "MULTIPLE_ZONES", "Più zone attive",
                    "Il locale ha più zone con capienze separate: la capienza unica non si può applicare.");
        }
        UUID id = active.isEmpty()
                ? (zones.isEmpty() ? store.insertZone("Locale", 1) : zones.getFirst().id())
                : active.getFirst().id();
        DiningStore.ZoneRow zone = store.zone(id).orElseThrow();
        ledger.changePhysicalCapacity(id, capacity, Instant.now(clock));
        // il registro ha già incrementato la versione della zona: si attiva con quella nuova
        store.updateZone(id, zone.version() + 1, zone.name(), true, zone.sortOrder());
        audit(actor, "VENUE_CAPACITY", id, Map.of("capacity", capacity));
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
                c.lastStart(), c.slotMinutes(), c.defaultDurationMinutes(), validFrom, c.validTo(), c.active());
        UUID windowId = id;
        if (id == null) {
            windowId = store.insertWindow(row);
        } else {
            store.updateWindow(row);
        }
        recompute(c.zoneId(), previous == null ? null : previous.zoneId());
        audit(actor, "SERVICE_WINDOW", windowId, Map.of("isoDay", c.isoDay()));
        return store.window(windowId).orElseThrow();
    }

    /** Una fascia già usata da prenotazioni non si cancella: viene disattivata. */
    @Transactional
    public boolean deleteWindow(UUID actor, UUID id) {
        DiningStore.WindowRow w = store.window(id).orElseThrow(() -> ApiErrors.notFound("La fascia non esiste."));
        boolean used = store.windowHasReservations(id);
        if (used) {
            store.updateWindow(new DiningStore.WindowRow(w.id(), w.zoneId(), w.isoDay(), w.name(), w.firstStart(), w.lastStart(),
                    w.slotMinutes(), w.defaultDurationMinutes(), w.validFrom(), w.validTo(), false));
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
        if (c.pendingExpiryMinutesBeforeStart() < 0 || c.pendingExpiryMinutesBeforeStart() > 1440) {
            throw ApiErrors.invalid("pendingExpiryMinutesBeforeStart", "La scadenza va da 0 a 24 ore prima dell'arrivo.");
        }
        if (c.webBookingEnabled() && store.zones().stream().noneMatch(DiningStore.ZoneRow::active)) {
            throw ApiErrors.invalid("webBookingEnabled", "Prima di aprire le richieste dal sito attiva almeno una zona con la sua capienza.");
        }
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        store.upsertPolicy(today, c.webBookingEnabled(), c.minLeadMinutes(), c.maxAdvanceDays(),
                c.pendingExpiryMinutesBeforeStart(), actor);
        audit(actor, "BOOKING_POLICY", null, Map.of("webBookingEnabled", c.webBookingEnabled()));
        return store.policyOn(today).orElseThrow();
    }

    // --- eccezioni ----------------------------------------------------------------------------------------------

    @Transactional
    /** Fascia chiusa agli arrivi in una data ({@code kind = CLOSED}, l'unico tipo dalla V12). */
    public DiningStore.ExceptionRow addException(UUID actor, LocalDate date, UUID windowId, String kind, String reason) {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        if (date == null || date.isBefore(today)) {
            throw ApiErrors.invalid("serviceDate", "Scegli una data da oggi in poi.");
        }
        if (kind != null && !"CLOSED".equals(kind)) {
            throw ApiErrors.invalid("kind", "La capienza è unica per tutto il locale: per una data si può solo chiudere agli arrivi.");
        }
        if (windowId != null) {
            store.window(windowId).orElseThrow(() -> ApiErrors.invalid("serviceWindowId", "La fascia scelta non esiste."));
        }
        if (store.exceptionsOn(date).stream().anyMatch(e -> java.util.Objects.equals(e.serviceWindowId(), windowId))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXCEPTION_EXISTS", "Eccezione già presente",
                    "Per questa data e fascia c'è già un'eccezione: eliminala prima di crearne un'altra.");
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (cleanReason != null && cleanReason.length() > 140) {
            throw ApiErrors.invalid("reason", "Motivo troppo lungo (massimo 140 caratteri).");
        }
        UUID id = store.insertClosedException(date, windowId, cleanReason, actor);
        audit(actor, "BOOKING_EXCEPTION", id, Map.of("kind", "CLOSED", "date", date.toString()));
        return store.exception(id).orElseThrow();
    }

    @Transactional
    public void deleteException(UUID actor, UUID id) {
        store.exception(id).orElseThrow(() -> ApiErrors.notFound("L'eccezione non esiste."));
        store.deleteException(id);
        audit(actor, "BOOKING_EXCEPTION", id, Map.of("deleted", true));
    }

    // --- chiusure e aperture straordinarie del locale (FASE 7, req. 10) ---------------------------------------

    /**
     * Chiusura o apertura straordinaria su un giorno o su un intervallo (FIX-1, req. 6.6): una riga per intervallo,
     * salvata in una transazione; il vincolo di esclusione della V12 impedisce giorni sovrapposti anche con salvataggi
     * contemporanei. Le prenotazioni già presenti non vengono mai cancellate: il risultato elenca i giorni coinvolti.
     */
    @Transactional
    public OpeningResult addOpeningException(UUID actor, OpeningCommand c) {
        OpeningCommand clean = validate(c, null);
        UUID id = store.insertOpeningException(clean.from(), clean.to(), clean.closed(), clean.opensAt(), clean.closesAt(), clean.note());
        audit(actor, "OPENING_EXCEPTION", id, Map.of("from", clean.from().toString(), "to", clean.to().toString(), "closed", clean.closed()));
        return result(id, clean);
    }

    @Transactional
    public OpeningResult updateOpeningException(UUID actor, UUID id, OpeningCommand c) {
        store.openingException(id).orElseThrow(() -> ApiErrors.notFound("La data straordinaria non esiste."));
        OpeningCommand clean = validate(c, id);
        store.updateOpeningException(id, clean.from(), clean.to(), clean.closed(), clean.opensAt(), clean.closesAt(), clean.note());
        audit(actor, "OPENING_EXCEPTION", id, Map.of("from", clean.from().toString(), "to", clean.to().toString(),
                "closed", clean.closed(), "updated", true));
        return result(id, clean);
    }

    @Transactional
    public void deleteOpeningException(UUID actor, UUID id) {
        store.openingException(id).orElseThrow(() -> ApiErrors.notFound("La data straordinaria non esiste."));
        store.deleteOpeningException(id);
        audit(actor, "OPENING_EXCEPTION", id, Map.of("deleted", true));
    }

    private OpeningCommand validate(OpeningCommand c, UUID self) {
        LocalDate today = BusinessTime.serviceDate(Instant.now(clock));
        LocalDate from = c.from();
        LocalDate to = c.to() == null ? from : c.to();
        if (from == null) {
            throw ApiErrors.invalid("from", "Scegli il giorno (o il primo giorno dell'intervallo).");
        }
        if (from.isBefore(today)) {
            throw ApiErrors.invalid("from", "Scegli una data da oggi in poi.");
        }
        if (to.isBefore(from)) {
            throw ApiErrors.invalid("to", "L'ultimo giorno non può essere prima del primo.");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_OPENING_RANGE_DAYS) {
            throw ApiErrors.invalid("to", "Un intervallo può durare al massimo un anno.");
        }
        if (!c.closed() && (c.opensAt() == null || c.closesAt() == null || c.opensAt().equals(c.closesAt()))) {
            throw ApiErrors.invalid("opensAt", "Per un'apertura straordinaria indica l'orario di apertura e di chiusura.");
        }
        String note = c.note() == null || c.note().isBlank() ? null : c.note().strip();
        if (note != null && note.length() > 140) {
            throw ApiErrors.invalid("note", "Nota troppo lunga (massimo 140 caratteri).");
        }
        List<DiningStore.OpeningExceptionRow> overlapping = store.openingExceptionsBetween(from, to).stream()
                .filter(e -> !e.id().equals(self)).toList();
        if (!overlapping.isEmpty()) {
            DiningStore.OpeningExceptionRow first = overlapping.getFirst();
            throw new ApiException(HttpStatus.CONFLICT, "EXCEPTION_OVERLAPS", "Date sovrapposte",
                    "Questi giorni si sovrappongono a " + (first.closed() ? "una chiusura" : "un'apertura") + " straordinaria già "
                            + "inserita (" + first.day() + (first.endDay().equals(first.day()) ? "" : " – " + first.endDay())
                            + "): modificala o eliminala prima.",
                    Map.of("overlaps", overlapping.stream().map(e -> Map.of("id", e.id(), "from", e.day(), "to", e.endDay(),
                            "closed", e.closed())).toList()));
        }
        return new OpeningCommand(from, to, c.closed(), c.closed() ? null : c.opensAt(), c.closed() ? null : c.closesAt(), note);
    }

    private OpeningResult result(UUID id, OpeningCommand c) {
        List<DiningStore.DayReservations> days = store.activeReservationsBetween(c.from(), c.to());
        long active = days.stream().mapToLong(d -> d.pending() + d.confirmed()).sum();
        return new OpeningResult(store.openingException(id).orElseThrow(), active, c.closed() ? days : List.of());
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
