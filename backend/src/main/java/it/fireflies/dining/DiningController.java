package it.fireflies.dining;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

/**
 * Configurazione delle prenotazioni. Lettura per staff e titolare (servono zone e tavoli per confermare);
 * scrittura solo titolare, con doppio controllo: regola sull'URL e {@code @PreAuthorize}.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DiningController {

    record ZoneCreate(String name) {
    }

    record ZoneUpdate(@NotNull Integer version, String name, @NotNull Integer physicalCapacity, @NotNull Boolean active,
            Integer sortOrder) {
    }

    record AreaRequest(@NotNull UUID zoneId, String name, Boolean active, Integer sortOrder) {
    }

    record TableRequest(@NotNull UUID areaId, String code, @NotNull Integer minSeats, @NotNull Integer maxSeats,
            Boolean active, Integer sortOrder) {
    }

    record WindowRequest(@NotNull UUID zoneId, @NotNull Integer isoDay, String name, @NotNull LocalTime firstStart,
            @NotNull LocalTime lastStart, @NotNull Integer slotMinutes, @NotNull Integer defaultDurationMinutes,
            Integer maxCovers, LocalDate validFrom, LocalDate validTo, Boolean active) {

        DiningService.WindowCommand command() {
            return new DiningService.WindowCommand(zoneId, isoDay, name, firstStart, lastStart, slotMinutes,
                    defaultDurationMinutes, maxCovers, validFrom, validTo, active == null || active);
        }
    }

    record PolicyRequest(@NotNull Boolean webBookingEnabled, @NotNull Integer minLeadMinutes, @NotNull Integer maxAdvanceDays,
            @NotNull Integer maxPartySizeWeb, @NotNull Integer pendingExpiryMinutesBeforeStart) {
    }

    record ExceptionRequest(@NotNull LocalDate serviceDate, UUID serviceWindowId, @NotNull String kind,
            Integer capacityOverride, String reason) {
    }

    record CapacityCellRequest(@NotNull Integer isoDay, @NotNull String band, Integer maxCovers) {
    }

    record CapacityMatrixRequest(@NotNull List<@Valid CapacityCellRequest> cells) {
    }

    record OpeningExceptionRequest(@NotNull LocalDate day, @NotNull Boolean closed, LocalTime opensAt, LocalTime closesAt, String note) {
    }

    private final DiningService dining;

    DiningController(DiningService dining) {
        this.dining = dining;
    }

    @GetMapping("/api/v1/admin/booking-config")
    @PreAuthorize("hasRole('STAFF')")
    DiningService.Config config() {
        return dining.config();
    }

    @PostMapping("/api/v1/admin/capacity-zones")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<DiningStore.ZoneRow> createZone(@AuthenticationPrincipal GestoreDetails actor, @RequestBody ZoneCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.createZone(actor.id(), body.name()));
    }

    @PutMapping("/api/v1/admin/capacity-zones/{id}")
    @PreAuthorize("hasRole('OWNER')")
    DiningStore.ZoneRow updateZone(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody ZoneUpdate body) {
        return dining.updateZone(actor.id(), id, body.version(), new DiningService.ZoneCommand(body.name(),
                body.physicalCapacity(), body.active(), body.sortOrder() == null ? 0 : body.sortOrder()));
    }

    @PostMapping("/api/v1/admin/areas")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<DiningStore.AreaRow> createArea(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody AreaRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.saveArea(actor.id(), null, body.zoneId(), body.name(),
                true, body.sortOrder() == null ? 0 : body.sortOrder()));
    }

    @PutMapping("/api/v1/admin/areas/{id}")
    @PreAuthorize("hasRole('OWNER')")
    DiningStore.AreaRow updateArea(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id, @Valid @RequestBody AreaRequest body) {
        return dining.saveArea(actor.id(), id, body.zoneId(), body.name(), body.active() == null || body.active(),
                body.sortOrder() == null ? 0 : body.sortOrder());
    }

    @DeleteMapping("/api/v1/admin/areas/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<Void> deleteArea(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        dining.deleteArea(actor.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/tables")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<DiningStore.TableRow> createTable(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody TableRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.saveTable(actor.id(), null, body.areaId(), body.code(),
                body.minSeats(), body.maxSeats(), true, body.sortOrder() == null ? 0 : body.sortOrder()));
    }

    @PutMapping("/api/v1/admin/tables/{id}")
    @PreAuthorize("hasRole('OWNER')")
    DiningStore.TableRow updateTable(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id, @Valid @RequestBody TableRequest body) {
        return dining.saveTable(actor.id(), id, body.areaId(), body.code(), body.minSeats(), body.maxSeats(),
                body.active() == null || body.active(), body.sortOrder() == null ? 0 : body.sortOrder());
    }

    /** {@code 204} se cancellato, {@code 200} con {@code deactivated: true} se aveva uno storico. */
    @DeleteMapping("/api/v1/admin/tables/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<?> deleteTable(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        return dining.deleteTable(actor.id(), id) ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(java.util.Map.of("deactivated", true));
    }

    @PostMapping("/api/v1/admin/service-windows")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<DiningStore.WindowRow> createWindow(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody WindowRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.saveWindow(actor.id(), null, body.command()));
    }

    @PutMapping("/api/v1/admin/service-windows/{id}")
    @PreAuthorize("hasRole('OWNER')")
    DiningStore.WindowRow updateWindow(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id, @Valid @RequestBody WindowRequest body) {
        return dining.saveWindow(actor.id(), id, body.command());
    }

    @DeleteMapping("/api/v1/admin/service-windows/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<?> deleteWindow(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        return dining.deleteWindow(actor.id(), id) ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(java.util.Map.of("deactivated", true));
    }

    @PutMapping("/api/v1/admin/booking-policy")
    @PreAuthorize("hasRole('OWNER')")
    DiningStore.PolicyRow updatePolicy(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody PolicyRequest body) {
        return dining.updatePolicy(actor.id(), new DiningService.PolicyCommand(body.webBookingEnabled(), body.minLeadMinutes(),
                body.maxAdvanceDays(), body.maxPartySizeWeb(), body.pendingExpiryMinutesBeforeStart()));
    }

    @PostMapping("/api/v1/admin/booking-exceptions")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<DiningStore.ExceptionRow> addException(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody ExceptionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.addException(actor.id(), body.serviceDate(),
                body.serviceWindowId(), body.kind(), body.capacityOverride(), body.reason()));
    }

    @DeleteMapping("/api/v1/admin/booking-exceptions/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<Void> deleteException(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        dining.deleteException(actor.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/admin/capacity-matrix")
    @PreAuthorize("hasRole('STAFF')")
    DiningService.CapacityMatrix capacityMatrix() {
        return dining.capacityMatrix();
    }

    @PutMapping("/api/v1/admin/capacity-matrix")
    @PreAuthorize("hasRole('OWNER')")
    DiningService.CapacityMatrix saveCapacityMatrix(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody CapacityMatrixRequest body) {
        return dining.saveCapacityMatrix(actor.id(), body.cells().stream()
                .map(c -> new DiningService.CapacityCell(c.isoDay(), Band.parse(c.band()), true, c.maxCovers())).toList());
    }

    @PostMapping("/api/v1/admin/opening-exceptions")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<java.util.Map<String, Object>> addOpeningException(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody OpeningExceptionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dining.addOpeningException(actor.id(), body.day(), body.closed(),
                body.opensAt(), body.closesAt(), body.note()));
    }

    @DeleteMapping("/api/v1/admin/opening-exceptions/{id}")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<Void> deleteOpeningException(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        dining.deleteOpeningException(actor.id(), id);
        return ResponseEntity.noContent().build();
    }
}
