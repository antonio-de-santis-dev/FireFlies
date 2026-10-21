package it.fireflies.reservation;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.dining.Band;

/** Calendario di oggi e riepilogo della capienza per i gestori (STAFF e titolare). Mai in cache: dati personali. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OperationsController {

    private final OperationsService operations;

    OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/api/v1/admin/reservations/today")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<OperationsService.Today> today(@RequestParam(required = false) String band) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.today(Band.parse(band)));
    }

    @GetMapping("/api/v1/admin/capacity-summary")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<OperationsService.Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String band) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.summary(from, to, Band.parse(band)));
    }
}
