package it.fireflies.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.capacity.CapacityLedger;
import it.fireflies.capacity.Occupancy;
import it.fireflies.capacity.OccupancyInterval;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;
import it.fireflies.common.BusinessTime;
import it.fireflies.dining.BookingCalendar;
import it.fireflies.dining.DiningStore;
import it.fireflies.notification.NotificationOutbox;

/**
 * Prenotazioni V1 (BOOKING_RULES): il cliente chiede, il gestore decide. Ogni comando è una transazione PostgreSQL;
 * i posti si contano solo nel registro di occupazione della FASE 2 ({@link CapacityLedger}), con l'ordine dei lock
 * prenotazione → zona → quanti → tavoli. Nessuna conferma e nessuna assegnazione di tavoli automatiche.
 */
@Service
public class ReservationService {

    public static final String PRIVACY_VERSION = "2026-10";
    static final String AGGREGATE = "RESERVATION";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Set<String> REJECT_REASONS = Set.of("NO_CAPACITY", "VENUE_DECISION", "DUPLICATE", "OTHER");
    private static final Set<String> CANCEL_REASONS = Set.of("CUSTOMER_REQUEST", "VENUE_DECISION", "DUPLICATE", "OTHER");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_IT = DateTimeFormatter.ofPattern("d/M/yyyy");

    public record WebRequest(LocalDate date, LocalTime time, int guests, String name, String phone, String email,
            String notes, boolean privacyAccepted, String website) {
    }

    public record Receipt(String reference, String status, LocalDate date, LocalTime time, int guests, String message) {
    }

    public record ManualRequest(String source, LocalDate date, LocalTime time, Integer durationMinutes, int guests,
            String name, String phone, String email, String notes, String internalNote, UUID zoneId, boolean confirmNow,
            List<UUID> tableIds, boolean needsCallback) {
    }

    public record StatusChange(int version, String status, String reason, String note, UUID zoneId, List<UUID> tableIds,
            boolean overrideSeats, String overrideReason) {
    }

    public record Update(int version, LocalDate date, LocalTime time, Integer durationMinutes, Integer guests, UUID zoneId,
            String name, String phone, String email, String notes, String internalNote, Boolean needsCallback) {
    }

    public record TablesChange(int version, List<UUID> tableIds, boolean overrideSeats, String overrideReason) {
    }

    /** Chi agisce: id e se è titolare (alcune operazioni sono solo del titolare, BOOKING_RULES §10). */
    public record Actor(UUID id, boolean owner) {
    }

    private final ReservationStore store;
    private final CapacityLedger ledger;
    private final BookingCalendar calendar;
    private final NotificationOutbox outbox;
    private final WebRequestLimiter limiter;
    private final AuditLog audit;
    private final Clock clock;

    ReservationService(ReservationStore store, CapacityLedger ledger, BookingCalendar calendar, NotificationOutbox outbox,
            WebRequestLimiter limiter, AuditLog audit, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.calendar = calendar;
        this.outbox = outbox;
        this.limiter = limiter;
        this.audit = audit;
        this.clock = clock;
    }

    // =========================================================================================================
    // Richieste dal sito (WEB): sempre PENDING, mai confermate dall'API pubblica (BOOKING_RULES §2, §5)
    // =========================================================================================================

    @Transactional
    public Receipt createWeb(WebRequest r, UUID idempotencyKey, String clientIp) {
        if (idempotencyKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Richiesta incompleta",
                    "Manca l'intestazione Idempotency-Key: ricarica la pagina e riprova.");
        }
        if (r.website() != null && !r.website().isBlank()) {
            // campo nascosto compilato: è un programma automatico, non una persona
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Richiesta non valida",
                    "La richiesta non può essere accettata.");
        }
        String name = text(r.name(), "name", 2, 80, "Scrivi un nome per la prenotazione (da 2 a 80 caratteri).");
        String phone = PhoneNumbers.normalize(r.phone()).orElseThrow(() ->
                ApiErrors.invalid("phone", "Scrivi un numero di telefono valido, es. 333 123 4567."));
        String email = email(r.email());
        String notes = optional(r.notes(), "notes", 500);
        String fingerprint = fingerprint(r.date(), r.time(), r.guests(), name, phone, email, notes);

        Optional<ReservationStore.Row> previous = store.byIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", "Richiesta già usata",
                        "Questa richiesta è già stata inviata con dati diversi: ricarica la pagina per inviarne una nuova.");
            }
            return receipt(previous.get()); // stesso invio ripetuto (doppio clic, rete instabile): stessa risposta
        }
        long wait = limiter.acquire(clientIp);
        if (wait > 0) {
            throw rateLimited(wait, "Hai inviato molte richieste in poco tempo: riprova più tardi o chiamaci.");
        }
        if (!r.privacyAccepted()) {
            throw ApiErrors.invalid("privacyAccepted", "Per inviare la richiesta devi dichiarare di aver letto l'informativa privacy.");
        }

        Instant now = Instant.now(clock);
        LocalDate today = BusinessTime.serviceDate(now);
        DiningStore.PolicyRow policy = calendar.policy(today).filter(DiningStore.PolicyRow::webBookingEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WEB_BOOKING_DISABLED",
                        "Richieste online chiuse", "Al momento il locale non accetta richieste dal sito: chiamaci."));
        if (r.guests() < 1 || r.guests() > policy.maxPartySizeWeb()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PARTY_TOO_LARGE", "Gruppo numeroso",
                    "Dal sito si può chiedere un tavolo fino a " + policy.maxPartySizeWeb()
                            + " persone: per gruppi più grandi chiamaci.", Map.of("maxPartySize", policy.maxPartySizeWeb()));
        }
        Slot slot = webSlot(r.date(), r.time(), policy, now);
        if (store.webRequestsFromPhoneSince(phone, now.minus(Duration.ofDays(1))) >= 3) {
            throw rateLimited(3600, "Da questo numero sono già arrivate diverse richieste oggi: per altre chiamaci.");
        }
        // controllo preventivo, solo informativo (senza lock): quello vincolante avviene alla conferma (§5)
        for (CapacityLedger.QuantumState q : ledger.read(slot.window().zoneId(), slot.start(), slot.end())) {
            if (q.exclusiveEventId() != null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "EVENT_BLOCKS_SLOT", "Serata riservata",
                        "In quell'orario c'è un evento: guarda la pagina Eventi.");
            }
            if (q.free() < r.guests()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FULLY_BOOKED", "Orario completo",
                        "In quell'orario siamo al completo: scegli un altro orario o chiamaci.");
            }
        }

        UUID id = store.insert(new ReservationStore.NewRow(newReference(), "PENDING", "WEB", r.date(), slot.start(), slot.end(),
                r.guests(), slot.window().id(), slot.window().zoneId(), name, phone, email, notes, null, PRIVACY_VERSION, now,
                idempotencyKey, fingerprint, null));
        store.history(id, null, "PENDING", null, null, null);
        ReservationStore.Row row = store.find(id).orElseThrow();
        notify(row, "RESERVATION_NEW_FOR_VENUE", "created");
        notify(row, "RESERVATION_RECEIVED", "created");
        audit.record(null, AuditAction.RESERVATION_CREATED, AGGREGATE, id.toString(),
                Map.of("source", "WEB", "guests", r.guests(), "ref", row.publicRef()));
        return receipt(row);
    }

    record Slot(DiningStore.WindowRow window, Instant start, Instant end) {
    }

    /** Orario chiesto dal sito: deve essere uno degli arrivi di una fascia aperta (§4, §5). */
    private Slot webSlot(LocalDate date, LocalTime time, DiningStore.PolicyRow policy, Instant now) {
        if (date == null || time == null) {
            throw ApiErrors.invalid("date", "Scegli giorno e orario.");
        }
        LocalDate today = BusinessTime.serviceDate(now);
        if (date.isAfter(today.plusDays(policy.maxAdvanceDays()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_FAR", "Data troppo lontana",
                    "Si può chiedere un tavolo al massimo " + policy.maxAdvanceDays() + " giorni prima.");
        }
        DiningStore.WindowRow window = calendar.windowsOn(date).stream().filter(w -> arrivalOf(w, time)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SLOT_NOT_AVAILABLE",
                        "Orario non disponibile", "Questo orario non è tra quelli prenotabili: scegline uno dall'elenco."));
        if (calendar.closed(date, window.id())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "DAY_CLOSED", "Giorno chiuso",
                    "In questa data il locale non accetta prenotazioni per questo orario.");
        }
        Instant start = BusinessTime.toInstant(date, time);
        if (start.isBefore(now.plus(Duration.ofMinutes(policy.minLeadMinutes())))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_EARLY", "Troppo presto",
                    "Le richieste dal sito vanno inviate almeno " + leadText(policy.minLeadMinutes())
                            + " prima: per oggi chiamaci.");
        }
        return new Slot(window, start, start.plus(Duration.ofMinutes(window.defaultDurationMinutes())));
    }

    static boolean arrivalOf(DiningStore.WindowRow w, LocalTime time) {
        int minute = time.toSecondOfDay() / 60;
        int first = w.firstStart().toSecondOfDay() / 60;
        int last = w.lastStart().toSecondOfDay() / 60;
        return time.getSecond() == 0 && minute >= first && minute <= last && (minute - first) % w.slotMinutes() == 0;
    }

    // =========================================================================================================
    // Inserimento da parte dei gestori (telefono, email): anche fuori dagli slot, con avvisi (§4)
    // =========================================================================================================

    @Transactional
    public ReservationViews.Detail createManual(Actor actor, ManualRequest r) {
        String source = r.source() == null ? "PHONE" : r.source().toUpperCase(Locale.ROOT);
        if (!source.equals("PHONE") && !source.equals("EMAIL")) {
            throw ApiErrors.invalid("source", "Indica se la richiesta è arrivata per telefono o per email.");
        }
        if (r.guests() < 1 || r.guests() > 50) {
            throw ApiErrors.invalid("guests", "Le persone vanno da 1 a 50.");
        }
        String name = text(r.name(), "name", 2, 80, "Scrivi il nome del cliente (da 2 a 80 caratteri).");
        String phone = PhoneNumbers.normalize(r.phone()).orElseThrow(() ->
                ApiErrors.invalid("phone", "Il telefono è obbligatorio: scrivilo con il prefisso se non è italiano."));
        String email = email(r.email());
        String notes = optional(r.notes(), "notes", 500);
        String internal = optional(r.internalNote(), "internalNote", 500);
        Instant now = Instant.now(clock);
        Timing t = timing(r.date(), r.time(), r.durationMinutes(), r.zoneId(), now);

        UUID id = store.insert(new ReservationStore.NewRow(newReference(), "PENDING", source, r.date(), t.start(), t.end(),
                r.guests(), t.window().id(), t.zoneId(), name, phone, email, notes, internal, null, null, null, null, actor.id()));
        store.history(id, null, "PENDING", null, null, actor.id());
        if (r.needsCallback()) {
            ReservationStore.Row row = store.find(id).orElseThrow();
            store.updateDetails(id, row.reservationDate(), row.startsAt(), row.endsAt(), null, row.guestCount(),
                    row.serviceWindowId(), row.zoneId(), name, phone, email, notes, internal, true, actor.id());
        }
        audit.record(actor.id(), AuditAction.RESERVATION_CREATED, AGGREGATE, id.toString(),
                Map.of("source", source, "guests", r.guests(), "confirmNow", r.confirmNow()));
        if (r.confirmNow()) {
            ReservationStore.Row pending = store.lock(id).orElseThrow();
            confirm(actor, pending, t.zoneId(), r.tableIds(), false, null, null);
        } else {
            notify(store.find(id).orElseThrow(), "RESERVATION_RECEIVED", "created");
        }
        return detail(id, t.warnings());
    }

    record Timing(DiningStore.WindowRow window, UUID zoneId, Instant start, Instant end, List<String> warnings) {
    }

    /** Orario per un inserimento o una modifica dei gestori: vincoli rigidi solo dove servono (passato, allineamento). */
    private Timing timing(LocalDate date, LocalTime time, Integer durationMinutes, UUID zoneId, Instant now) {
        if (date == null || time == null) {
            throw ApiErrors.invalid("date", "Indica giorno e orario di arrivo.");
        }
        if (time.getMinute() % 15 != 0 || time.getSecond() != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ALIGNED_15_MIN", "Orario non valido",
                    "Gli orari sono a passi di 15 minuti (es. 19:00, 19:15).");
        }
        UUID zone = zoneId != null ? zoneId : singleActiveZone();
        DiningStore.ZoneRow zoneRow = calendar.zone(zone).filter(DiningStore.ZoneRow::active)
                .orElseThrow(() -> ApiErrors.invalid("zoneId", "Scegli una zona attiva."));
        DiningStore.WindowRow window = calendar.windowFor(zoneRow.id(), date, time).orElseThrow(() ->
                new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_SERVICE_WINDOW", "Nessuna fascia",
                        "Per quel giorno non è configurata nessuna fascia in questa zona: aggiungila in «Capienza e tavoli»."));
        int minutes = durationMinutes != null ? durationMinutes : window.defaultDurationMinutes();
        if (minutes < 15 || minutes > 720 || minutes % 15 != 0) {
            throw ApiErrors.invalid("durationMinutes", "La durata va da 15 minuti a 12 ore, a passi di 15 minuti.");
        }
        Instant start = BusinessTime.toInstant(date, time);
        if (!start.isAfter(now)) {
            throw ApiErrors.invalid("time", "L'orario è già passato.");
        }
        List<String> warnings = new ArrayList<>();
        if (!arrivalOf(window, time)) {
            warnings.add("OUTSIDE_SLOTS");
        }
        if (calendar.closed(date, window.id())) {
            warnings.add("DAY_CLOSED");
        }
        calendar.policy(BusinessTime.serviceDate(now)).ifPresent(p -> {
            if (start.isBefore(now.plus(Duration.ofMinutes(p.minLeadMinutes())))) {
                warnings.add("SHORT_NOTICE");
            }
        });
        return new Timing(window, zoneRow.id(), start, start.plus(Duration.ofMinutes(minutes)), warnings);
    }

    private UUID singleActiveZone() {
        List<DiningStore.ZoneRow> zones = calendar.activeZones();
        if (zones.size() != 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ZONE_REQUIRED", "Zona da scegliere",
                    zones.isEmpty() ? "Nessuna zona è attiva: il titolare deve prima impostare la capienza."
                            : "Il locale ha più zone: scegli in quale sistemare il tavolo.");
        }
        return zones.getFirst().id();
    }

    // =========================================================================================================
    // Cambi di stato (BOOKING_RULES §2): una sola transazione con registro, tavoli, storico, audit, outbox
    // =========================================================================================================

    @Transactional
    public ReservationViews.Detail changeStatus(Actor actor, UUID id, StatusChange c) {
        ReservationStore.Row row = store.lock(id).orElseThrow(ReservationService::notFound);
        String target = c.status() == null ? "" : c.status().toUpperCase(Locale.ROOT);
        if (row.status().equals(target)) {
            return detail(id, List.of()); // doppio clic: nessun effetto, nessuno storico, nessuna notifica
        }
        if (row.version() != c.version()) {
            throw ApiErrors.versionConflict();
        }
        Instant now = Instant.now(clock);
        switch (row.status() + "->" + target) {
            case "PENDING->CONFIRMED" -> confirm(actor, row, c.zoneId(), c.tableIds(), c.overrideSeats(), c.overrideReason(), c.note());
            case "PENDING->REJECTED" -> close(actor, row, "REJECTED", reason(c.reason(), REJECT_REASONS), c.note(),
                    "RESERVATION_REJECTED");
            case "PENDING->CANCELLED" -> close(actor, row, "CANCELLED", reason(c.reason(), CANCEL_REASONS), c.note(),
                    "RESERVATION_CANCELLED");
            case "CONFIRMED->CANCELLED" -> {
                if (!now.isBefore(row.startsAt())) {
                    throw transition("Dopo l'inizio non si annulla: segna «Completata» o «Non presentato».");
                }
                ledger.release(occupancy(row));
                store.releaseAssignments(id);
                outbox.supersede(AGGREGATE, id, List.of("RESERVATION_CONFIRMED", "RESERVATION_UPDATED"));
                close(actor, row, "CANCELLED", reason(c.reason(), CANCEL_REASONS), c.note(), "RESERVATION_CANCELLED");
            }
            case "CONFIRMED->COMPLETED" -> {
                if (now.isBefore(row.startsAt())) {
                    throw transition("Si può segnare «Completata» solo dall'orario di arrivo in poi.");
                }
                finish(actor, row, "COMPLETED", c.note(), now);
            }
            case "CONFIRMED->NO_SHOW" -> {
                if (now.isBefore(row.startsAt().plus(Duration.ofMinutes(15)))) {
                    throw transition("«Non presentato» si può segnare da 15 minuti dopo l'orario di arrivo.");
                }
                finish(actor, row, "NO_SHOW", c.note(), now);
            }
            case "NO_SHOW->COMPLETED", "COMPLETED->NO_SHOW" -> {
                if (!actor.owner()) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Solo il titolare",
                            "La correzione tra «Completata» e «Non presentato» spetta al titolare.");
                }
                if (now.isAfter(row.startsAt().plus(Duration.ofHours(48)))) {
                    throw transition("La correzione è possibile entro 48 ore dall'arrivo.");
                }
                store.updateStatus(id, target, null, row.zoneId(), row.occupiedUntil(), actor.id(), false, false);
                store.history(id, row.status(), target, null, optional(c.note(), "note", 300), actor.id());
                audit.record(actor.id(), AuditAction.RESERVATION_STATUS_CHANGED, AGGREGATE, id.toString(),
                        Map.of("from", row.status(), "to", target, "correction", true));
            }
            default -> throw transition("Da «" + row.status() + "» non si può passare a «" + target + "».");
        }
        return detail(id, List.of());
    }

    /** PENDING → CONFIRMED (§3.3): zona determinata, occupazione nel registro, tavoli facoltativi. */
    private void confirm(Actor actor, ReservationStore.Row row, UUID requestedZone, List<UUID> tableIds, boolean overrideSeats,
            String overrideReason, String note) {
        Instant now = Instant.now(clock);
        if (!row.startsAt().isAfter(now)) {
            throw transition("L'orario è già passato: la richiesta non si può più confermare.");
        }
        UUID zoneId = requestedZone != null ? requestedZone : row.zoneId() != null ? row.zoneId() : singleActiveZone();
        calendar.zone(zoneId).filter(DiningStore.ZoneRow::active).orElseThrow(() -> ApiErrors.invalid("zoneId", "Scegli una zona attiva."));
        ReservationStore.Row placed = withZone(row, zoneId, row.endsAt());
        ledger.occupy(occupancy(placed)); // CAPACITY_EXCEEDED / EVENT_BLOCKS_SLOT: tutta la transazione si annulla
        store.updateStatus(row.id(), "CONFIRMED", null, zoneId, row.endsAt(), actor.id(), true, false);
        store.history(row.id(), row.status(), "CONFIRMED", null, optional(note, "note", 300), actor.id());
        if (tableIds != null && !tableIds.isEmpty()) {
            assignTables(actor, store.find(row.id()).orElseThrow(), tableIds, overrideSeats, overrideReason);
        }
        audit.record(actor.id(), AuditAction.RESERVATION_STATUS_CHANGED, AGGREGATE, row.id().toString(),
                Map.of("from", row.status(), "to", "CONFIRMED", "guests", row.guestCount()));
        notify(store.find(row.id()).orElseThrow(), "RESERVATION_CONFIRMED", "v" + (row.version() + 1));
    }

    private void close(Actor actor, ReservationStore.Row row, String status, String reason, String note, String template) {
        store.updateStatus(row.id(), status, reason, row.zoneId(), row.occupiedUntil(), actor.id(), false, true);
        store.history(row.id(), row.status(), status, reason, optional(note, "note", 300), actor.id());
        audit.record(actor.id(), AuditAction.RESERVATION_STATUS_CHANGED, AGGREGATE, row.id().toString(),
                Map.of("from", row.status(), "to", status, "reason", reason));
        notify(store.find(row.id()).orElseThrow(), template, "v" + (row.version() + 1));
    }

    /** COMPLETED / NO_SHOW: i quanti futuri tornano liberi (uscita anticipata, assenza). */
    private void finish(Actor actor, ReservationStore.Row row, String status, String note, Instant now) {
        Instant freeFrom = ceilToQuantum(now);
        if (!freeFrom.isAfter(row.startsAt())) {
            freeFrom = row.startsAt().plus(OccupancyInterval.QUANTUM);
        }
        Instant occupiedUntil = row.occupiedUntil();
        if (freeFrom.isBefore(occupiedUntil)) {
            ledger.release(new Occupancy(row.zoneId(), new OccupancyInterval(freeFrom, occupiedUntil), row.guestCount()));
            store.shortenAssignments(row.id(), freeFrom);
            occupiedUntil = freeFrom;
        }
        store.updateStatus(row.id(), status, null, row.zoneId(), occupiedUntil, actor.id(), false, true);
        store.history(row.id(), row.status(), status, null, optional(note, "note", 300), actor.id());
        audit.record(actor.id(), AuditAction.RESERVATION_STATUS_CHANGED, AGGREGATE, row.id().toString(),
                Map.of("from", row.status(), "to", status));
    }

    // =========================================================================================================
    // Modifica (§3.4, §6) e tavoli (§8)
    // =========================================================================================================

    @Transactional
    public ReservationViews.Detail update(Actor actor, UUID id, Update u) {
        ReservationStore.Row row = store.lock(id).orElseThrow(ReservationService::notFound);
        if (row.version() != u.version()) {
            throw ApiErrors.versionConflict();
        }
        boolean open = row.status().equals("PENDING") || row.status().equals("CONFIRMED");
        String name = u.name() == null ? row.customerName() : text(u.name(), "name", 2, 80, "Scrivi il nome del cliente (da 2 a 80 caratteri).");
        String phone = u.phone() == null ? row.customerPhone() : PhoneNumbers.normalize(u.phone())
                .orElseThrow(() -> ApiErrors.invalid("phone", "Scrivi un numero di telefono valido."));
        String email = u.email() == null ? row.customerEmail() : email(u.email());
        String notes = u.notes() == null ? row.customerNotes() : optional(u.notes(), "notes", 500);
        String internal = u.internalNote() == null ? row.internalNote() : optional(u.internalNote(), "internalNote", 500);
        boolean callback = u.needsCallback() == null ? row.needsCallback() : u.needsCallback();

        Map<String, Object> changed = new LinkedHashMap<>();
        LocalDate date = row.reservationDate();
        Instant start = row.startsAt();
        Instant end = row.endsAt();
        int guests = row.guestCount();
        UUID windowId = row.serviceWindowId();
        UUID zoneId = row.zoneId();
        List<String> warnings = List.of();
        boolean timing = u.date() != null || u.time() != null || u.durationMinutes() != null || u.guests() != null || u.zoneId() != null;
        if (timing) {
            if (!open) {
                throw transition("Una prenotazione chiusa non si può spostare: puoi cambiare solo note e recapiti.");
            }
            ZonedDateTime local = row.startsAt().atZone(BusinessTime.ZONE);
            LocalDate newDate = u.date() != null ? u.date() : local.toLocalDate();
            LocalTime newTime = u.time() != null ? u.time() : local.toLocalTime();
            int minutes = u.durationMinutes() != null ? u.durationMinutes() : (int) Duration.between(row.startsAt(), row.endsAt()).toMinutes();
            Timing t = timing(newDate, newTime, minutes, u.zoneId() != null ? u.zoneId() : row.zoneId(), Instant.now(clock));
            guests = u.guests() != null ? u.guests() : row.guestCount();
            if (guests < 1 || guests > 50) {
                throw ApiErrors.invalid("guests", "Le persone vanno da 1 a 50.");
            }
            date = newDate;
            start = t.start();
            end = t.end();
            windowId = t.window().id();
            zoneId = t.zoneId();
            warnings = t.warnings();
            if (!start.equals(row.startsAt()) || !end.equals(row.endsAt())) {
                changed.put("time", true);
            }
            if (guests != row.guestCount()) {
                changed.put("guests", guests);
            }
            if (!Objects.equals(zoneId, row.zoneId())) {
                changed.put("zone", true);
            }
        }
        Instant occupiedUntil = row.occupiedUntil();
        if (row.status().equals("CONFIRMED") && !changed.isEmpty()) {
            // libera il vecchio intervallo e occupa il nuovo insieme: se non sta, nulla cambia (409)
            ledger.change(occupancy(row), new Occupancy(zoneId, new OccupancyInterval(start, end), guests));
            occupiedUntil = end;
        }
        if (!Objects.equals(name, row.customerName()) || !Objects.equals(phone, row.customerPhone())
                || !Objects.equals(email, row.customerEmail()) || !Objects.equals(notes, row.customerNotes())) {
            changed.put("customer", true);
        }
        if (!Objects.equals(internal, row.internalNote())) {
            changed.put("internalNote", true);
        }
        if (callback != row.needsCallback()) {
            changed.put("needsCallback", callback);
        }
        if (changed.isEmpty()) {
            return detail(id, warnings);
        }
        store.updateDetails(id, date, start, end, occupiedUntil, guests, windowId, zoneId, name, phone, email, notes, internal,
                callback, actor.id());
        if (row.status().equals("CONFIRMED") && (changed.containsKey("time") || changed.containsKey("zone"))) {
            List<UUID> tables = store.assignments(id, true).stream().map(ReservationStore.AssignmentRow::tableId).toList();
            if (!tables.isEmpty()) {
                // stessi tavoli sul nuovo intervallo: se sono occupati da altri → 409 TABLE_CONFLICT, nulla cambia
                if (!Objects.equals(zoneId, row.zoneId())) {
                    store.releaseAssignments(id);
                } else {
                    reassign(actor, store.find(id).orElseThrow(), tables);
                }
            }
        }
        audit.record(actor.id(), AuditAction.RESERVATION_UPDATED, AGGREGATE, id.toString(), changed);
        if (row.status().equals("CONFIRMED") && (changed.containsKey("time") || changed.containsKey("guests") || changed.containsKey("zone"))) {
            notify(store.find(id).orElseThrow(), "RESERVATION_UPDATED", "v" + (row.version() + 1));
        }
        return detail(id, warnings);
    }

    @Transactional
    public ReservationViews.Detail changeTables(Actor actor, UUID id, TablesChange c) {
        ReservationStore.Row row = store.lock(id).orElseThrow(ReservationService::notFound);
        if (row.version() != c.version()) {
            throw ApiErrors.versionConflict();
        }
        if (!row.status().equals("CONFIRMED")) {
            throw transition("I tavoli si assegnano solo alle prenotazioni confermate.");
        }
        assignTables(actor, row, c.tableIds() == null ? List.of() : c.tableIds(), c.overrideSeats(), c.overrideReason());
        store.updateDetails(id, row.reservationDate(), row.startsAt(), row.endsAt(), row.occupiedUntil(), row.guestCount(),
                row.serviceWindowId(), row.zoneId(), row.customerName(), row.customerPhone(), row.customerEmail(),
                row.customerNotes(), row.internalNote(), row.needsCallback(), actor.id());
        return detail(id, minSeatsWarning(row, c.tableIds()));
    }

    /** Sostituisce i tavoli (§8): stessa zona, righe bloccate in ordine di id, posti sufficienti salvo eccezione del titolare. */
    private void assignTables(Actor actor, ReservationStore.Row row, List<UUID> tableIds, boolean overrideSeats, String overrideReason) {
        List<UUID> ids = tableIds.stream().distinct().sorted().toList();
        List<DiningStore.TableRow> tables = calendar.tables(ids);
        if (tables.size() != ids.size() || tables.stream().anyMatch(t -> !t.active())) {
            throw ApiErrors.invalid("tableIds", "Uno dei tavoli scelti non esiste o non è attivo.");
        }
        if (tables.stream().anyMatch(t -> !t.zoneId().equals(row.zoneId()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TABLE_OUTSIDE_ZONE", "Tavolo di un'altra zona",
                    "I tavoli devono essere nella zona della prenotazione.");
        }
        int seats = tables.stream().mapToInt(DiningStore.TableRow::maxSeats).sum();
        String reason = null;
        if (!ids.isEmpty() && seats < row.guestCount()) {
            if (!overrideSeats) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SEATS_BELOW_PARTY_SIZE", "Posti insufficienti",
                        "I tavoli scelti hanno " + seats + " posti per " + row.guestCount() + " persone.",
                        Map.of("seats", seats, "guests", row.guestCount()));
            }
            if (!actor.owner()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SEATS_OVERRIDE_FORBIDDEN", "Solo il titolare",
                        "L'eccezione sui posti del tavolo spetta al titolare.");
            }
            reason = optional(overrideReason, "overrideReason", 200);
            if (reason == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "OVERRIDE_REASON_REQUIRED", "Motivo obbligatorio",
                        "Scrivi il motivo dell'eccezione (es. «aggiungiamo uno sgabello»).");
            }
        }
        store.lockTables(ids);
        store.releaseAssignments(row.id());
        for (UUID tableId : ids) {
            store.assign(row.id(), tableId, row.startsAt(), row.occupiedUntil(), reason != null, reason != null ? actor.id() : null,
                    reason, actor.id());
        }
        audit.record(actor.id(), AuditAction.RESERVATION_TABLES_ASSIGNED, AGGREGATE, row.id().toString(),
                Map.of("tables", ids.size(), "seats", seats));
        if (reason != null) {
            audit.record(actor.id(), AuditAction.TABLE_SEATS_OVERRIDE, AGGREGATE, row.id().toString(),
                    Map.of("seats", seats, "guests", row.guestCount()));
        }
    }

    private void reassign(Actor actor, ReservationStore.Row row, List<UUID> tableIds) {
        List<ReservationStore.AssignmentRow> current = store.assignments(row.id(), true);
        boolean override = current.stream().anyMatch(ReservationStore.AssignmentRow::seatsOverride);
        String reason = current.stream().map(ReservationStore.AssignmentRow::overrideReason).filter(Objects::nonNull).findFirst().orElse(null);
        List<UUID> ids = tableIds.stream().distinct().sorted().toList();
        store.lockTables(ids);
        store.releaseAssignments(row.id());
        for (UUID tableId : ids) {
            store.assign(row.id(), tableId, row.startsAt(), row.occupiedUntil(), override, override ? actor.id() : null, reason, actor.id());
        }
    }

    private List<String> minSeatsWarning(ReservationStore.Row row, List<UUID> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return List.of();
        }
        int min = calendar.tables(tableIds).stream().mapToInt(DiningStore.TableRow::minSeats).sum();
        return min > row.guestCount() ? List.of("MIN_SEATS_ABOVE_PARTY") : List.of();
    }

    // =========================================================================================================
    // Scadenza delle richieste non gestite (§6)
    // =========================================================================================================

    /** Richieste in attesa troppo vicine all'arrivo → REJECTED/EXPIRED. Un gestore che conferma nello stesso istante vince o perde intero. */
    @Transactional
    public int expirePending() {
        Instant now = Instant.now(clock);
        int minutes = calendar.policy(BusinessTime.serviceDate(now)).map(DiningStore.PolicyRow::pendingExpiryMinutesBeforeStart).orElse(60);
        int expired = 0;
        for (UUID id : store.lockExpiring(now, minutes, 200)) {
            ReservationStore.Row row = store.find(id).orElseThrow();
            store.updateStatus(id, "REJECTED", "EXPIRED", row.zoneId(), null, null, false, true);
            store.history(id, "PENDING", "REJECTED", "EXPIRED", null, null);
            audit.record(null, AuditAction.RESERVATION_EXPIRED, AGGREGATE, id.toString(), Map.of("ref", row.publicRef()));
            notify(store.find(id).orElseThrow(), "RESERVATION_EXPIRED", "expired");
            expired++;
        }
        return expired;
    }

    // =========================================================================================================
    // Letture
    // =========================================================================================================

    @Transactional(readOnly = true)
    public ReservationViews.Detail detail(UUID id) {
        return detail(id, List.of());
    }

    private ReservationViews.Detail detail(UUID id, List<String> warnings) {
        return views.detail(store.find(id).orElseThrow(ReservationService::notFound), warnings);
    }

    private ReservationViews views;

    @org.springframework.beans.factory.annotation.Autowired
    void setViews(ReservationViews views) {
        this.views = views;
    }

    // =========================================================================================================
    // Supporto
    // =========================================================================================================

    private void notify(ReservationStore.Row row, String template, String suffix) {
        ZonedDateTime local = row.startsAt().atZone(BusinessTime.ZONE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("riferimento", row.publicRef());
        payload.put("data", local.toLocalDate().format(DATE_IT));
        payload.put("ora", local.toLocalTime().toString());
        payload.put("persone", row.guestCount());
        payload.put("nome", row.customerName());
        String key = "RES:" + row.id() + ":" + template + ":" + suffix;
        if (template.equals("RESERVATION_NEW_FOR_VENUE")) {
            outbox.enqueue(key + ":EMAIL", AGGREGATE, row.id(), NotificationOutbox.Channel.EMAIL,
                    NotificationOutbox.Recipient.STAFF, views.venueEmail(), template, payload);
            return;
        }
        if (row.customerEmail() != null) {
            outbox.enqueue(key + ":EMAIL", AGGREGATE, row.id(), NotificationOutbox.Channel.EMAIL,
                    NotificationOutbox.Recipient.CUSTOMER, row.customerEmail(), template, payload);
        }
        if (!template.equals("RESERVATION_RECEIVED")) {
            outbox.enqueue(key + ":SMS", AGGREGATE, row.id(), NotificationOutbox.Channel.SMS,
                    NotificationOutbox.Recipient.CUSTOMER, row.customerPhone(), template, payload);
        }
    }

    private Receipt receipt(ReservationStore.Row row) {
        ZonedDateTime local = row.startsAt().atZone(BusinessTime.ZONE);
        return new Receipt(row.publicRef(), row.status(), local.toLocalDate(), local.toLocalTime(), row.guestCount(),
                "Richiesta ricevuta: non è ancora una prenotazione. Il locale ti risponderà al più presto"
                        + (row.customerEmail() != null ? " via email o telefono." : " al telefono indicato."));
    }

    private static Occupancy occupancy(ReservationStore.Row row) {
        return new Occupancy(row.zoneId(), new OccupancyInterval(row.startsAt(), row.occupiedUntil() != null ? row.occupiedUntil() : row.endsAt()),
                row.guestCount());
    }

    private static ReservationStore.Row withZone(ReservationStore.Row r, UUID zoneId, Instant occupiedUntil) {
        return new ReservationStore.Row(r.id(), r.publicRef(), r.status(), r.statusReason(), r.source(), r.reservationDate(),
                r.startsAt(), r.endsAt(), occupiedUntil, r.guestCount(), r.serviceWindowId(), zoneId, r.customerName(),
                r.customerPhone(), r.customerEmail(), r.customerNotes(), r.internalNote(), r.needsCallback(),
                r.idempotencyKey(), r.fingerprint(), r.createdBy(), r.handledBy(), r.confirmedAt(), r.closedAt(),
                r.createdAt(), r.updatedAt(), r.version());
    }

    static Instant ceilToQuantum(Instant instant) {
        long q = OccupancyInterval.QUANTUM.toSeconds();
        long seconds = instant.getEpochSecond() + (instant.getNano() > 0 ? 1 : 0);
        return Instant.ofEpochSecond(Math.floorDiv(seconds + q - 1, q) * q);
    }

    private String newReference() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("FF-");
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            if (!store.refExists(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Impossibile generare un riferimento univoco");
    }

    private static String reason(String reason, Set<String> allowed) {
        if (reason == null || !allowed.contains(reason)) {
            throw ApiErrors.invalid("reason", "Scegli il motivo.");
        }
        return reason;
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object v : values) {
                digest.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String value, String field, int min, int max, String message) {
        String v = value == null ? "" : value.strip();
        if (v.length() < min || v.length() > max) {
            throw ApiErrors.invalid(field, message);
        }
        return v;
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiErrors.invalid(field, "Testo troppo lungo (massimo " + max + " caratteri).");
        }
        return v;
    }

    private static String email(String value) {
        String v = optional(value, "email", 254);
        if (v != null && !EMAIL.matcher(v).matches()) {
            throw ApiErrors.invalid("email", "Controlla l'email, es. nome@esempio.it (oppure lasciala vuota).");
        }
        return v;
    }

    private static String leadText(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60 == 1 ? "un'ora" : minutes / 60 + " ore") : minutes + " minuti";
    }

    private static ApiException rateLimited(long seconds, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Troppe richieste", message,
                Map.of("retryAfterSeconds", seconds));
    }

    private static ApiException transition(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Operazione non possibile", message);
    }

    static ApiException notFound() {
        return ApiErrors.notFound("La prenotazione non esiste.");
    }
}
