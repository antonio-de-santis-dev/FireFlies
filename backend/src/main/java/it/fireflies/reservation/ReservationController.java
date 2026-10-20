package it.fireflies.reservation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.common.ApiErrors;
import it.fireflies.security.GestoreDetails;
import it.fireflies.security.Role;

/**
 * API delle prenotazioni. Pubblico: orari richiedibili e invio di una richiesta (sempre «in attesa»). Gestori
 * (staff e titolare): elenco, dettaglio, inserimento manuale, cambi di stato, modifica, tavoli.
 * Tutte le scritture passano dal controllo CSRF, anche quella pubblica.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ReservationController {

    record WebBody(LocalDate date, LocalTime time, Integer guests, String name, String phone, String email, String notes,
            Boolean privacyAccepted, String website, UUID eventId) {
    }

    record ManualBody(String source, LocalDate date, LocalTime time, Integer durationMinutes, Integer guests, String name,
            String phone, String email, String notes, String internalNote, UUID zoneId, Boolean confirmNow,
            List<UUID> tableIds, Boolean needsCallback, UUID eventId) {
    }

    record StatusBody(@NotNull Integer version, String status, String reason, String note, UUID zoneId, List<UUID> tableIds,
            Boolean overrideSeats, String overrideReason) {
    }

    record UpdateBody(@NotNull Integer version, LocalDate date, LocalTime time, Integer durationMinutes, Integer guests,
            UUID zoneId, String name, String phone, String email, String notes, String internalNote, Boolean needsCallback) {
    }

    record TablesBody(@NotNull Integer version, List<UUID> tableIds, Boolean overrideSeats, String overrideReason) {
    }

    record ListResponse(List<ReservationViews.Summary> items, long total, int page, int size, Map<String, Long> counts) {
    }

    private final ReservationService reservations;
    private final ReservationQueries queries;
    private final BookingOptionsService options;

    ReservationController(ReservationService reservations, ReservationQueries queries, BookingOptionsService options) {
        this.reservations = reservations;
        this.queries = queries;
        this.options = options;
    }

    // --- pubblico ------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/public/booking-options")
    ResponseEntity<BookingOptionsService.Options> bookingOptions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer guests) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options.options(date, guests));
    }

    @PostMapping("/api/v1/public/reservations")
    ResponseEntity<ReservationService.Receipt> createWeb(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody WebBody body, HttpServletRequest request) {
        ReservationService.Receipt receipt = reservations.createWeb(new ReservationService.WebRequest(body.date(), body.time(),
                body.guests() == null ? 0 : body.guests(), body.name(), body.phone(), body.email(), body.notes(),
                Boolean.TRUE.equals(body.privacyAccepted()), body.website(), body.eventId()), uuid(key), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
    }

    // --- gestori -------------------------------------------------------------------------------------------------

    @GetMapping("/api/v1/admin/reservations")
    @PreAuthorize("hasRole('STAFF')")
    ListResponse list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String source,
            @RequestParam(required = false) String q, @RequestParam(required = false) Boolean needsCallback,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        List<String> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        int pageSize = Math.clamp(size, 1, 200);
        int pageNumber = Math.max(0, page);
        ReservationQueries.Result result = queries.search(new ReservationStore.Filter(from, to, statuses,
                source == null || source.isBlank() ? null : source.toUpperCase(Locale.ROOT), q, needsCallback, pageNumber, pageSize));
        return new ListResponse(result.items(), result.total(), pageNumber, pageSize, result.counts());
    }

    @GetMapping("/api/v1/admin/reservations/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ReservationViews.Detail detail(@PathVariable UUID id) {
        return reservations.detail(id);
    }

    @PostMapping("/api/v1/admin/reservations")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<ReservationViews.Detail> createManual(@AuthenticationPrincipal GestoreDetails gestore, @RequestBody ManualBody b) {
        ReservationViews.Detail detail = reservations.createManual(actor(gestore), new ReservationService.ManualRequest(b.source(),
                b.date(), b.time(), b.durationMinutes(), b.guests() == null ? 0 : b.guests(), b.name(), b.phone(), b.email(),
                b.notes(), b.internalNote(), b.zoneId(), Boolean.TRUE.equals(b.confirmNow()), b.tableIds(),
                Boolean.TRUE.equals(b.needsCallback()), b.eventId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PatchMapping("/api/v1/admin/reservations/{id}/status")
    @PreAuthorize("hasRole('STAFF')")
    ReservationViews.Detail changeStatus(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id,
            @RequestBody StatusBody b) {
        requireVersion(b.version());
        return reservations.changeStatus(actor(gestore), id, new ReservationService.StatusChange(b.version(), b.status(),
                b.reason(), b.note(), b.zoneId(), b.tableIds(), Boolean.TRUE.equals(b.overrideSeats()), b.overrideReason()));
    }

    @PatchMapping("/api/v1/admin/reservations/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ReservationViews.Detail update(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id, @RequestBody UpdateBody b) {
        requireVersion(b.version());
        return reservations.update(actor(gestore), id, new ReservationService.Update(b.version(), b.date(), b.time(),
                b.durationMinutes(), b.guests(), b.zoneId(), b.name(), b.phone(), b.email(), b.notes(), b.internalNote(),
                b.needsCallback()));
    }

    @PutMapping("/api/v1/admin/reservations/{id}/tables")
    @PreAuthorize("hasRole('STAFF')")
    ReservationViews.Detail changeTables(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id,
            @RequestBody TablesBody b) {
        requireVersion(b.version());
        return reservations.changeTables(actor(gestore), id, new ReservationService.TablesChange(b.version(), b.tableIds(),
                Boolean.TRUE.equals(b.overrideSeats()), b.overrideReason()));
    }

    @GetMapping("/api/v1/admin/capacity")
    @PreAuthorize("hasRole('STAFF')")
    ReservationQueries.DayCapacity capacity(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.day(date);
    }

    private static ReservationService.Actor actor(GestoreDetails gestore) {
        return new ReservationService.Actor(gestore.id(), gestore.role() == Role.OWNER);
    }

    private static void requireVersion(Integer version) {
        if (version == null) {
            throw ApiErrors.invalid("version", "Manca la versione: ricarica la prenotazione.");
        }
    }

    private static UUID uuid(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(key.strip());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.invalid("Idempotency-Key", "Intestazione Idempotency-Key non valida: ricarica la pagina.");
        }
    }
}
