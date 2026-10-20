package it.fireflies.event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.security.GestoreDetails;
import it.fireflies.security.Role;

/**
 * Eventi. Pubblico: elenco e scheda (niente numeri di posti). Gestori: STAFF e titolare creano, modificano, pubblicano;
 * evento non esclusivo e annullamento con prenotazioni attive solo del titolare (controllati nel servizio).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class EventController {

    record EventBody(String slug, String title, String kind, LocalDate date, LocalTime startTime, LocalTime endTime, UUID zoneId,
            Boolean exclusive, String bookingMode, Integer capacityCovers, String artist, String summary, String body,
            UUID posterMediaId, List<UUID> mediaIds, Integer version, Boolean acknowledgePending, Boolean visible) {

        EventService.EventCommand command() {
            return new EventService.EventCommand(slug, title, kind, date, startTime, endTime, zoneId, exclusive, bookingMode,
                    capacityCovers, artist, summary, body, posterMediaId, mediaIds);
        }
    }

    record PublishBody(@NotNull Integer version, Boolean acknowledgePending) {
    }

    record CancelBody(@NotNull Integer version, Boolean cancelReservations) {
    }

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @GetMapping("/api/v1/public/events")
    ResponseEntity<List<EventService.PublicEvent>> publicEvents() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(events.publicEvents());
    }

    @GetMapping("/api/v1/public/events/{slug}")
    ResponseEntity<EventService.PublicEvent> publicEvent(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(events.publicEvent(slug));
    }

    @GetMapping("/api/v1/admin/events")
    @PreAuthorize("hasRole('STAFF')")
    List<EventService.AdminEvent> list() {
        return events.list();
    }

    @GetMapping("/api/v1/admin/events/{id}")
    @PreAuthorize("hasRole('STAFF')")
    EventService.AdminEvent detail(@PathVariable UUID id) {
        return events.detail(id);
    }

    @PostMapping("/api/v1/admin/events")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<EventService.AdminEvent> create(@AuthenticationPrincipal GestoreDetails gestore, @RequestBody EventBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(events.create(actor(gestore), body.command(),
                Boolean.TRUE.equals(body.visible()), Boolean.TRUE.equals(body.acknowledgePending())));
    }

    @PutMapping("/api/v1/admin/events/{id}")
    @PreAuthorize("hasRole('STAFF')")
    EventService.AdminEvent update(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id, @RequestBody EventBody body) {
        if (body.version() == null) {
            throw it.fireflies.common.ApiErrors.invalid("version", "Manca la versione: ricarica l'evento.");
        }
        return events.update(actor(gestore), id, body.version(), body.command(), Boolean.TRUE.equals(body.acknowledgePending()),
                body.visible());
    }

    @PostMapping("/api/v1/admin/events/{id}/publish")
    @PreAuthorize("hasRole('STAFF')")
    EventService.AdminEvent publish(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id,
            @Valid @RequestBody PublishBody body) {
        return events.publish(actor(gestore), id, body.version(), Boolean.TRUE.equals(body.acknowledgePending()));
    }

    @PostMapping("/api/v1/admin/events/{id}/cancel")
    @PreAuthorize("hasRole('STAFF')")
    EventService.AdminEvent cancel(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id,
            @Valid @RequestBody CancelBody body) {
        return events.cancel(actor(gestore), id, body.version(), Boolean.TRUE.equals(body.cancelReservations()));
    }

    @DeleteMapping("/api/v1/admin/events/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id) {
        events.delete(actor(gestore), id);
        return ResponseEntity.noContent().build();
    }

    private static EventService.Actor actor(GestoreDetails gestore) {
        return new EventService.Actor(gestore.id(), gestore.role() == Role.OWNER);
    }
}
