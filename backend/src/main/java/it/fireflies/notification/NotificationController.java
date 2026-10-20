package it.fireflies.notification;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.security.GestoreDetails;

/** Nuovo invio manuale di una notifica non riuscita o incerta (STAFF e titolare, con CSRF e audit). */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class NotificationController {

    private final NotificationDispatcher dispatcher;

    NotificationController(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/api/v1/admin/notifications/{id}/resend")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> resend(@AuthenticationPrincipal GestoreDetails gestore, @PathVariable UUID id) {
        dispatcher.resend(gestore.id(), id);
        return ResponseEntity.accepted().build();
    }
}
