package it.fireflies.event;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.capacity.CapacityLedger;
import it.fireflies.capacity.OccupancyInterval;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;
import it.fireflies.common.BusinessTime;
import it.fireflies.dining.BookingCalendar;
import it.fireflies.dining.DiningStore;
import it.fireflies.media.MediaCatalog;
import it.fireflies.media.MediaRef;
import it.fireflies.reservation.ReservationService;

/**
 * Eventi (BOOKING_RULES §7): bozza, pubblicazione, modifica, annullamento. Un evento esclusivo pubblicato riserva i suoi
 * quanti nel registro della FASE 2 (un solo contatore di posti); le sue prenotazioni passano dalla conferma del gestore
 * come tutte le altre, con la quota dell'evento controllata in più.
 */
@Service
public class EventService {

    static final String AGGREGATE = "EVENT";
    private static final Duration MAX_LENGTH = Duration.ofHours(12);

    /** Dati di un evento dal gestore. {@code endTime} ≤ {@code startTime} = fine il giorno dopo (es. 22:00–02:00). */
    public record EventCommand(String slug, String title, String kind, LocalDate date, LocalTime startTime, LocalTime endTime,
            UUID zoneId, Boolean exclusive, String bookingMode, Integer capacityCovers, String artist, String summary,
            String body, UUID posterMediaId, List<UUID> mediaIds) {
    }

    /** Prenotazione in conflitto, senza dati personali. */
    public record Conflict(String reference, LocalDate date, LocalTime time, LocalTime endTime, int guests, String status) {
    }

    public record AdminEvent(UUID id, String slug, String title, String kind, String status, LocalDate date, LocalTime startTime,
            LocalTime endTime, UUID zoneId, boolean exclusive, String bookingMode, Integer capacityCovers, int confirmedCovers,
            long pendingCovers, long confirmedReservations, long pendingReservations, String artist, String summary, String body,
            MediaRef poster, List<MediaRef> media, Instant updatedAt, int version, List<String> warnings) {
    }

    /** Per il sito: niente numeri di posti (P-26), solo se si può chiedere un tavolo. */
    public record PublicEvent(UUID id, String slug, String title, String kind, String status, LocalDate date, LocalTime startTime,
            LocalTime endTime, String artist, String summary, List<String> body, MediaRef poster, List<MediaRef> media,
            Booking booking) {
    }

    /** {@code reason}: null se si può chiedere, altrimenti NONE, FULL, CLOSED, TOO_LATE, CANCELLED. */
    public record Booking(String mode, boolean open, String reason) {
    }

    /** Chi agisce: id e se è titolare. */
    public record Actor(UUID id, boolean owner) {
    }

    private final EventStore store;
    private final CapacityLedger ledger;
    private final BookingCalendar calendar;
    private final MediaCatalog media;
    private final ReservationService reservations;
    private final AuditLog audit;
    private final Clock clock;

    EventService(EventStore store, CapacityLedger ledger, BookingCalendar calendar, MediaCatalog media,
            ReservationService reservations, AuditLog audit, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.calendar = calendar;
        this.media = media;
        this.reservations = reservations;
        this.audit = audit;
        this.clock = clock;
    }

    // =========================================================================================================
    // Letture
    // =========================================================================================================

    @Transactional(readOnly = true)
    public List<AdminEvent> list() {
        return store.all().stream().map(r -> admin(r, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public AdminEvent detail(UUID id) {
        return admin(store.find(id).orElseThrow(EventService::notFound), List.of());
    }

    @Transactional(readOnly = true)
    public List<PublicEvent> publicEvents() {
        Instant now = Instant.now(clock);
        return store.visible(now).stream().map(r -> pub(r, now)).toList();
    }

    @Transactional(readOnly = true)
    public PublicEvent publicEvent(String slug) {
        Instant now = Instant.now(clock);
        return store.bySlug(slug).filter(r -> !r.status().equals("DRAFT")).map(r -> pub(r, now))
                .orElseThrow(EventService::notFound);
    }

    // =========================================================================================================
    // Bozza e modifica
    // =========================================================================================================

    /**
     * Nuovo evento con un solo salvataggio atomico (FASE 7, req. 4): {@code visible} vero = pubblicato nella stessa
     * transazione, con tutti i controlli della pubblicazione; se un controllo fallisce non resta nemmeno la bozza.
     */
    @Transactional
    public AdminEvent create(Actor actor, EventCommand c, boolean visible, boolean acknowledgePending) {
        EventStore.Values v = values(actor, c, null);
        UUID id = store.insert(v, actor.id());
        store.replaceMedia(id, mediaIds(c));
        audit.record(actor.id(), AuditAction.EVENT_CREATED, AGGREGATE, id.toString(), Map.of("kind", v.kind(), "exclusive", v.exclusive()));
        if (visible) {
            return publishLocked(actor, store.lock(id).orElseThrow(), acknowledgePending);
        }
        return detail(id);
    }

    /**
     * Modifica. Per un evento pubblicato, cambiare orario, zona o esclusività con prenotazioni confermate →
     * {@code 409 EVENT_HAS_CONFIRMED_RESERVATIONS}; altrimenti la riserva esclusiva si sposta con gli stessi controlli
     * della pubblicazione. Quota sotto i confermati → {@code 409 CAPACITY_BELOW_CONFIRMED}.
     * {@code visible}: null = lo stato resta com'è; vero = pubblica (bozza); falso = torna bozza (pubblicato), tutto
     * nella stessa transazione della modifica.
     */
    @Transactional
    public AdminEvent update(Actor actor, UUID id, int version, EventCommand c, boolean acknowledgePending, Boolean visible) {
        EventStore.Row row = store.lock(id).orElseThrow(EventService::notFound);
        if (row.version() != version) {
            throw ApiErrors.versionConflict();
        }
        if (row.status().equals("CANCELLED")) {
            throw transition("Un evento annullato non si modifica.");
        }
        if (Boolean.FALSE.equals(visible) && row.status().equals("PUBLISHED")) {
            row = unpublishLocked(actor, row);
        }
        EventStore.Values v = values(actor, c, row);
        if (v.capacityCovers() != null && v.capacityCovers() < row.confirmedCovers()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_BELOW_CONFIRMED", "Quota inferiore ai confermati",
                    "Per questo evento sono già confermate " + row.confirmedCovers() + " persone: la quota non può scendere sotto.",
                    Map.of("confirmedCovers", row.confirmedCovers()));
        }
        boolean placementChanged = !Objects.equals(v.startsAt(), row.startsAt()) || !Objects.equals(v.endsAt(), row.endsAt())
                || !Objects.equals(v.zoneId(), row.zoneId()) || v.exclusive() != row.exclusive();
        List<String> warnings = List.of();
        if (row.status().equals("PUBLISHED") && placementChanged) {
            if (store.countReservations(id, "CONFIRMED") > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "EVENT_HAS_CONFIRMED_RESERVATIONS", "Ci sono prenotazioni confermate",
                        "Per cambiare orario, zona o esclusività prima vanno gestite le prenotazioni confermate dell'evento.");
            }
            requireFuture(v.startsAt());
            ledger.unmarkExclusive(id);
            store.update(id, v);
            warnings = place(store.find(id).orElseThrow(), acknowledgePending);
            if (v.startsAt() != null && v.endsAt() != null) {
                store.movePending(id, v.startsAt(), v.endsAt(), BusinessTime.serviceDate(v.startsAt()));
            }
        } else {
            store.update(id, v);
        }
        store.replaceMedia(id, mediaIds(c));
        audit.record(actor.id(), AuditAction.EVENT_UPDATED, AGGREGATE, id.toString(), Map.of("placementChanged", placementChanged));
        if (Boolean.TRUE.equals(visible) && row.status().equals("DRAFT")) {
            return publishLocked(actor, store.find(id).orElseThrow(), acknowledgePending);
        }
        return admin(store.find(id).orElseThrow(), warnings);
    }

    /**
     * Elimina un evento (bozza, pubblicato o annullato) che non ha prenotazioni: la riserva esclusiva nel registro si
     * libera prima. Con prenotazioni attive → {@code 409 EVENT_HAS_ACTIVE_RESERVATIONS} (vanno annullate, oppure si
     * annulla l'evento); con solo storico chiuso → {@code 409 EVENT_HAS_HISTORY}: lo storico resta finché la pulizia
     * notturna non lo elimina, poi l'evento con lui.
     */
    @Transactional
    public void delete(Actor actor, UUID id) {
        EventStore.Row row = store.lock(id).orElseThrow(EventService::notFound);
        if (!store.reservationsOf(id, List.of("PENDING", "CONFIRMED")).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_HAS_ACTIVE_RESERVATIONS", "Ci sono prenotazioni attive",
                    "L'evento ha prenotazioni in attesa o confermate: annullale prima, oppure annulla l'evento.");
        }
        if (store.hasReservations(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_HAS_HISTORY", "C'è uno storico di prenotazioni",
                    "L'evento ha prenotazioni già chiuse: puoi nasconderlo o annullarlo. Verrà eliminato dalla pulizia "
                            + "automatica insieme al suo storico.");
        }
        ledger.unmarkExclusive(id);
        store.delete(id);
        audit.record(actor.id(), AuditAction.EVENT_DELETED, AGGREGATE, id.toString(),
                Map.of("title", row.title(), "status", row.status()));
    }

    // =========================================================================================================
    // Pubblicazione e annullamento
    // =========================================================================================================

    /**
     * Pubblica (§7.3). Evento esclusivo: se nei suoi quanti ci sono persone confermate di prenotazioni ordinarie (o un
     * altro evento esclusivo) → {@code 409 EVENT_CONFLICTS}, non forzabile; richieste in attesa sovrapposte →
     * {@code 409 EVENT_CONFLICTS} finché non si conferma di averle viste ({@code acknowledgePending}).
     */
    @Transactional
    public AdminEvent publish(Actor actor, UUID id, int version, boolean acknowledgePending) {
        EventStore.Row row = store.lock(id).orElseThrow(EventService::notFound);
        if (row.status().equals("PUBLISHED")) {
            return admin(row, List.of()); // doppio clic
        }
        if (row.version() != version) {
            throw ApiErrors.versionConflict();
        }
        return publishLocked(actor, row, acknowledgePending);
    }

    /** Pubblicazione di una riga già bloccata (dall'endpoint dedicato o dal salvataggio con «Visibile sul sito»). */
    private AdminEvent publishLocked(Actor actor, EventStore.Row row, boolean acknowledgePending) {
        if (row.status().equals("CANCELLED")) {
            throw transition("Un evento annullato non si ripubblica: creane uno nuovo.");
        }
        if (row.kind().equals("SERATA") && !row.timed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "EVENT_DATE_REQUIRED", "Data mancante",
                    "Per pubblicare una serata servono data, ora di inizio e ora di fine.");
        }
        if (row.timed()) {
            requireFuture(row.startsAt());
        }
        List<String> warnings = place(row, acknowledgePending);
        store.updateStatus(row.id(), "PUBLISHED");
        audit.record(actor.id(), AuditAction.EVENT_PUBLISHED, AGGREGATE, row.id().toString(),
                Map.of("exclusive", row.exclusive(), "warnings", warnings));
        return admin(store.find(row.id()).orElseThrow(), warnings);
    }

    /**
     * Nasconde dal sito un evento pubblicato (torna bozza). Solo senza prenotazioni attive: chi ha una richiesta o una
     * prenotazione per la serata la vedrebbe sparire → {@code 409 EVENT_HAS_ACTIVE_RESERVATIONS}. La riserva esclusiva
     * nel registro si libera.
     */
    private EventStore.Row unpublishLocked(Actor actor, EventStore.Row row) {
        if (!store.reservationsOf(row.id(), List.of("PENDING", "CONFIRMED")).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_HAS_ACTIVE_RESERVATIONS", "Ci sono prenotazioni attive",
                    "L'evento ha prenotazioni in attesa o confermate: per toglierlo dal sito annullale prima, oppure annulla "
                            + "l'evento (resta visibile come «Annullata»).");
        }
        ledger.unmarkExclusive(row.id());
        store.updateStatus(row.id(), "DRAFT");
        audit.record(actor.id(), AuditAction.EVENT_UNPUBLISHED, AGGREGATE, row.id().toString(), Map.of());
        return store.find(row.id()).orElseThrow();
    }

    /**
     * Annulla un evento pubblicato. Con prenotazioni attive serve il titolare; con {@code cancelReservations} tutte
     * passano ad «Annullata» (motivo EVENT_CANCELLED) nella stessa transazione, posti e quota rilasciati. I quanti
     * esclusivi tornano liberi solo quando l'evento non ha più prenotazioni confermate.
     */
    @Transactional
    public AdminEvent cancel(Actor actor, UUID id, int version, boolean cancelReservations) {
        EventStore.Row current = store.find(id).orElseThrow(EventService::notFound);
        if (current.status().equals("CANCELLED")) {
            return admin(current, List.of());
        }
        List<UUID> active = store.reservationsOf(id, List.of("PENDING", "CONFIRMED"));
        if (!active.isEmpty() && !actor.owner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Solo il titolare",
                    "L'evento ha prenotazioni attive: annullarlo spetta al titolare.");
        }
        if (cancelReservations) {
            reservations.lockInOrder(active); // prima le prenotazioni, poi l'evento (ordine globale dei lock)
        }
        EventStore.Row row = store.lock(id).orElseThrow(EventService::notFound);
        if (row.version() != version) {
            throw ApiErrors.versionConflict();
        }
        if (row.status().equals("DRAFT")) {
            throw transition("Una bozza non si annulla: si elimina.");
        }
        int cancelled = 0;
        if (cancelReservations) {
            for (UUID reservationId : store.reservationsOf(id, List.of("PENDING", "CONFIRMED"))) {
                reservations.cancelForEvent(new ReservationService.Actor(actor.id(), actor.owner()), reservationId);
                cancelled++;
            }
        }
        store.updateStatus(id, "CANCELLED");
        if (store.countReservations(id, "CONFIRMED") == 0) {
            ledger.unmarkExclusive(id);
        }
        audit.record(actor.id(), AuditAction.EVENT_CANCELLED, AGGREGATE, id.toString(),
                Map.of("cancelReservations", cancelReservations, "reservationsCancelled", cancelled));
        return admin(store.find(id).orElseThrow(), List.of());
    }

    // =========================================================================================================
    // Posizionamento nel registro
    // =========================================================================================================

    /** Riserva esclusiva (con i controlli dei conflitti) o, per un evento non esclusivo, l'avviso sulla quota. */
    private List<String> place(EventStore.Row row, boolean acknowledgePending) {
        if (!row.timed()) {
            return List.of();
        }
        List<UUID> zones = zonesOf(row);
        if (!row.exclusive()) {
            if (row.capacityCovers() == null) {
                return List.of();
            }
            int free = zones.stream().mapToInt(z -> ledger.read(z, row.startsAt(), row.endsAt()).stream()
                    .mapToInt(CapacityLedger.QuantumState::free).min().orElse(0)).sum();
            return row.capacityCovers() > free ? List.of("EVENT_QUOTA_NOT_GUARANTEED") : List.of();
        }
        OccupancyInterval interval = new OccupancyInterval(row.startsAt(), row.endsAt());
        boolean blocked = false;
        boolean otherEvent = false;
        for (UUID zone : zones) {
            List<CapacityLedger.QuantumState> conflicts = ledger.markExclusive(zone, row.id(), interval);
            blocked |= !conflicts.isEmpty();
            otherEvent |= conflicts.stream().anyMatch(q -> q.exclusiveEventId() != null && !q.exclusiveEventId().equals(row.id()));
        }
        if (blocked) {
            List<Conflict> confirmed = conflicts(zones, row, List.of("CONFIRMED", "COMPLETED", "NO_SHOW"));
            throw conflictError(otherEvent
                    ? "Nello stesso orario c'è già un altro evento esclusivo."
                    : "Nell'orario dell'evento ci sono prenotazioni già confermate: annullale o spostale prima di pubblicare.",
                    confirmed, List.of(), otherEvent, false);
        }
        List<Conflict> pending = conflicts(zones, row, List.of("PENDING"));
        if (!pending.isEmpty() && !acknowledgePending) {
            throw conflictError("Ci sono richieste in attesa nello stesso orario: pubblicando non potranno più essere confermate "
                    + "come prenotazioni ordinarie. Conferma di averle viste.", List.of(), pending, false, true);
        }
        return pending.isEmpty() ? List.of() : List.of("PENDING_REQUESTS_BLOCKED");
    }

    /** Zone coinvolte: quella dell'evento, oppure tutte le zone attive (evento su tutto il locale), in ordine di id. */
    private List<UUID> zonesOf(EventStore.Row row) {
        List<UUID> zones = row.zoneId() != null ? List.of(row.zoneId())
                : calendar.activeZones().stream().map(DiningStore.ZoneRow::id).toList();
        return zones.stream().sorted().toList();
    }

    private List<Conflict> conflicts(List<UUID> zones, EventStore.Row row, List<String> statuses) {
        return store.ordinaryOverlaps(zones, row.startsAt(), row.endsAt(), statuses).stream().map(o -> {
            ZonedDateTime start = o.startsAt().atZone(BusinessTime.ZONE);
            return new Conflict(o.reference(), start.toLocalDate(), start.toLocalTime(),
                    o.endsAt().atZone(BusinessTime.ZONE).toLocalTime(), o.guests(), o.status());
        }).toList();
    }

    private static ApiException conflictError(String message, List<Conflict> confirmed, List<Conflict> pending, boolean otherEvent,
            boolean acknowledgeable) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("confirmed", confirmed);
        context.put("pending", pending);
        context.put("otherEvent", otherEvent);
        context.put("acknowledgeable", acknowledgeable);
        return new ApiException(HttpStatus.CONFLICT, "EVENT_CONFLICTS", "Ci sono prenotazioni nello stesso orario", message, context);
    }

    // =========================================================================================================
    // Validazione
    // =========================================================================================================

    private EventStore.Values values(Actor actor, EventCommand c, EventStore.Row previous) {
        String title = text(c.title(), "title", 2, 120, "Dai un titolo alla serata (da 2 a 120 caratteri).");
        String kind = c.kind() == null ? "SERATA" : c.kind().toUpperCase(Locale.ROOT);
        if (!kind.equals("SERATA") && !kind.equals("FORMAT")) {
            throw ApiErrors.invalid("kind", "Scegli se è una serata con data o un format ricorrente.");
        }
        String mode = c.bookingMode() == null ? "NONE" : c.bookingMode().toUpperCase(Locale.ROOT);
        if (!mode.equals("NONE") && !mode.equals("REQUEST")) {
            throw ApiErrors.invalid("bookingMode", "Scegli se si può chiedere un tavolo per l'evento.");
        }
        Instant start = null;
        Instant end = null;
        if (c.date() != null || c.startTime() != null || c.endTime() != null) {
            if (c.date() == null || c.startTime() == null || c.endTime() == null) {
                throw ApiErrors.invalid("date", "Indica giorno, ora di inizio e ora di fine (oppure nessuno dei tre).");
            }
            if (c.startTime().getMinute() % 15 != 0 || c.endTime().getMinute() % 15 != 0 || c.startTime().getSecond() != 0
                    || c.endTime().getSecond() != 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ALIGNED_15_MIN", "Orario non valido",
                        "Gli orari degli eventi sono a passi di 15 minuti (es. 21:30, 21:45).");
            }
            start = BusinessTime.toInstant(c.date(), c.startTime());
            LocalDate endDate = c.endTime().isAfter(c.startTime()) ? c.date() : c.date().plusDays(1);
            end = BusinessTime.toInstant(endDate, c.endTime());
            if (Duration.between(start, end).compareTo(MAX_LENGTH) > 0) {
                throw ApiErrors.invalid("endTime", "Un evento dura al massimo 12 ore.");
            }
        }
        if (kind.equals("SERATA") && start == null && mode.equals("REQUEST")) {
            throw ApiErrors.invalid("date", "Per chiedere un tavolo la serata deve avere data e orari.");
        }
        boolean exclusive = c.exclusive() == null || c.exclusive();
        boolean wasExclusive = previous == null || previous.exclusive();
        if (!exclusive && wasExclusive && !actor.owner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXCLUSIVITY_OWNER_ONLY", "Solo il titolare",
                    "Un evento che condivide i posti con il servizio ordinario lo abilita solo il titolare.");
        }
        UUID zoneId = c.zoneId();
        List<DiningStore.ZoneRow> active = calendar.activeZones();
        if (zoneId != null) {
            if (active.stream().noneMatch(z -> z.id().equals(zoneId))) {
                throw ApiErrors.invalid("zoneId", "Scegli una zona attiva.");
            }
            if (active.size() < 2) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ZONE_NOT_SEPARATE", "Zona non separata",
                        "Il locale ha una sola zona: l'evento riguarda tutto il locale.");
            }
        }
        Integer capacity = c.capacityCovers();
        if (mode.equals("REQUEST") && capacity == null) {
            throw ApiErrors.invalid("capacityCovers", "Indica quante persone al massimo possono prenotare per l'evento.");
        }
        if (capacity != null) {
            int physical = (zoneId != null ? active.stream().filter(z -> z.id().equals(zoneId))
                    : active.stream()).mapToInt(DiningStore.ZoneRow::physicalCapacity).sum();
            if (capacity < 1 || capacity > physical) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "QUOTA_ABOVE_CAPACITY", "Quota non valida",
                        "La quota va da 1 alla capienza " + (zoneId != null ? "della zona" : "del locale") + " (" + physical
                                + " persone).", Map.of("field", "capacityCovers", "physicalCapacity", physical));
            }
        }
        media.requireReady(c.posterMediaId(), "posterMediaId");
        mediaIds(c).forEach(m -> media.requireReady(m, "mediaIds"));
        String slug = previous != null ? previous.slug() : uniqueSlug(c.slug() != null && !c.slug().isBlank() ? c.slug() : title);
        return new EventStore.Values(slug, title, kind, start, end, zoneId, exclusive, mode, capacity,
                optional(c.artist(), "artist", 120), optional(c.summary(), "summary", 300), optional(c.body(), "body", 5000),
                c.posterMediaId());
    }

    private void requireFuture(Instant start) {
        if (start != null && !start.isAfter(Instant.now(clock))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "EVENT_IN_PAST", "Data passata",
                    "L'evento inizia nel passato: controlla data e ora.");
        }
    }

    private String uniqueSlug(String source) {
        String base = Normalizer.normalize(source, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.length() > 70) {
            base = base.substring(0, 70).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = "evento";
        }
        String slug = base;
        for (int n = 2; store.slugTaken(slug, null); n++) {
            slug = base + "-" + n;
        }
        return slug;
    }

    private static List<UUID> mediaIds(EventCommand c) {
        List<UUID> ids = c.mediaIds() == null ? List.of() : c.mediaIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 12) {
            throw ApiErrors.invalid("mediaIds", "Al massimo 12 foto per evento.");
        }
        return ids;
    }

    // =========================================================================================================
    // Viste
    // =========================================================================================================

    private AdminEvent admin(EventStore.Row r, List<String> warnings) {
        Map<UUID, MediaRef> refs = media.refs(concat(r.posterMediaId(), store.media(r.id())));
        return new AdminEvent(r.id(), r.slug(), r.title(), r.kind(), r.status(), date(r.startsAt()), time(r.startsAt()),
                time(r.endsAt()), r.zoneId(), r.exclusive(), r.bookingMode(), r.capacityCovers(), r.confirmedCovers(),
                store.pendingCovers(r.id()), store.countReservations(r.id(), "CONFIRMED"), store.countReservations(r.id(), "PENDING"),
                r.artist(), r.summary(), r.body(), refs.get(r.posterMediaId()),
                store.media(r.id()).stream().map(refs::get).filter(Objects::nonNull).toList(), r.updatedAt(), r.version(), warnings);
    }

    private PublicEvent pub(EventStore.Row r, Instant now) {
        Map<UUID, MediaRef> refs = media.refs(concat(r.posterMediaId(), store.media(r.id())));
        List<String> body = r.body() == null ? List.of()
                : Arrays.stream(r.body().split("\\n\\s*\\n")).map(String::strip).filter(p -> !p.isEmpty()).toList();
        return new PublicEvent(r.id(), r.slug(), r.title(), r.kind(), r.status(), date(r.startsAt()), time(r.startsAt()),
                time(r.endsAt()), r.artist(), r.summary(), body, refs.get(r.posterMediaId()),
                store.media(r.id()).stream().map(refs::get).filter(Objects::nonNull).toList(), booking(r, now));
    }

    private Booking booking(EventStore.Row r, Instant now) {
        if (!r.bookingMode().equals("REQUEST")) {
            return new Booking("NONE", false, "NONE");
        }
        if (r.status().equals("CANCELLED")) {
            return new Booking("REQUEST", false, "CANCELLED");
        }
        DiningStore.PolicyRow policy = calendar.policy(BusinessTime.serviceDate(now)).orElse(null);
        if (policy == null || !policy.webBookingEnabled()) {
            return new Booking("REQUEST", false, "CLOSED");
        }
        if (r.startsAt() == null || r.startsAt().isBefore(now.plus(Duration.ofMinutes(policy.minLeadMinutes())))) {
            return new Booking("REQUEST", false, "TOO_LATE");
        }
        if (r.capacityCovers() == null || r.confirmedCovers() >= r.capacityCovers()) {
            return new Booking("REQUEST", false, "FULL");
        }
        return new Booking("REQUEST", true, null);
    }

    private static List<UUID> concat(UUID poster, List<UUID> others) {
        List<UUID> all = new ArrayList<>();
        if (poster != null) {
            all.add(poster);
        }
        all.addAll(others);
        return all;
    }

    private static LocalDate date(Instant i) {
        return i == null ? null : i.atZone(BusinessTime.ZONE).toLocalDate();
    }

    private static LocalTime time(Instant i) {
        return i == null ? null : i.atZone(BusinessTime.ZONE).toLocalTime();
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

    private static ApiException transition(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Operazione non possibile", message);
    }

    static ApiException notFound() {
        return ApiErrors.notFound("L'evento non esiste.");
    }
}
