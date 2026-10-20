package it.fireflies.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.capacity.CapacityLedger;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;

/**
 * Quota di un evento per il modulo prenotazioni (BOOKING_RULES §7.1). La quota non è un secondo contatore di posti:
 * è il massimo di persone vendibili per l'evento, controllato <b>in aggiunta</b> al registro della zona, nella stessa
 * transazione. La riga evento si blocca sempre <b>prima</b> della zona e dei quanti (ordine globale dei lock).
 */
@Service
public class EventBooking {

    /** Evento visto dal modulo prenotazioni. {@code capacity} null = nessuna prenotazione prevista. */
    public record BookableEvent(UUID id, String slug, String title, String status, String bookingMode, Instant startsAt,
            Instant endsAt, UUID zoneId, boolean exclusive, Integer capacity, int confirmed) {

        public boolean acceptsRequests() {
            return "PUBLISHED".equals(status) && "REQUEST".equals(bookingMode) && startsAt != null && endsAt != null
                    && capacity != null;
        }

        public int remaining() {
            return capacity == null ? 0 : Math.max(0, capacity - confirmed);
        }
    }

    private final EventStore store;
    private final CapacityLedger ledger;

    EventBooking(EventStore store, CapacityLedger ledger) {
        this.store = store;
        this.ledger = ledger;
    }

    public Optional<BookableEvent> find(UUID eventId) {
        return eventId == null ? Optional.empty() : store.find(eventId).map(EventBooking::view);
    }

    /** Blocca la riga evento (da chiamare prima di toccare il registro). */
    @Transactional(propagation = Propagation.MANDATORY)
    public BookableEvent lock(UUID eventId) {
        return store.lock(eventId).map(EventBooking::view).orElseThrow(() -> ApiErrors.notFound("L'evento non esiste."));
    }

    /**
     * Conferma di {@code guests} persone per l'evento: evento pubblicato e prenotabile, quota sufficiente.
     * Errori: {@code 409 INVALID_TRANSITION} (evento annullato o non prenotabile), {@code 409 EVENT_QUOTA_EXCEEDED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BookableEvent acquire(UUID eventId, int guests) {
        BookableEvent event = lock(eventId);
        if (!event.acceptsRequests()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Evento non prenotabile",
                    "CANCELLED".equals(event.status()) ? "L'evento è stato annullato: la richiesta non si può confermare."
                            : "L'evento non accetta prenotazioni.");
        }
        adjust(event, guests);
        return event;
    }

    /** Cambio di persone di una prenotazione confermata dell'evento (riga evento già bloccata o da bloccare). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void change(UUID eventId, int delta) {
        BookableEvent event = lock(eventId);
        if (delta > 0 && !"PUBLISHED".equals(event.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Evento annullato",
                    "L'evento è stato annullato: non si possono aggiungere persone.");
        }
        adjust(event, delta);
    }

    /**
     * Restituisce alla quota le persone di una prenotazione annullata (dopo il cambio di stato). Se l'evento è annullato
     * e non ha più prenotazioni confermate, i suoi quanti esclusivi tornano al servizio ordinario (§7.3).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void giveBack(UUID eventId, int guests) {
        BookableEvent event = lock(eventId);
        store.addConfirmedCovers(eventId, -guests);
        if ("CANCELLED".equals(event.status()) && store.countReservations(eventId, "CONFIRMED") == 0) {
            ledger.unmarkExclusive(eventId);
        }
    }

    private void adjust(BookableEvent event, int delta) {
        if (delta > 0 && event.capacity() != null && event.confirmed() + delta > event.capacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_QUOTA_EXCEEDED", "Posti dell'evento esauriti",
                    "Per «" + event.title() + "» restano " + event.remaining() + " posti, la richiesta è per " + delta
                            + " persone.", Map.of("eventId", event.id(), "remainingCovers", event.remaining()));
        }
        if (delta != 0) {
            store.addConfirmedCovers(event.id(), delta);
        }
    }

    /** Prenotazioni attive dell'evento (per l'annullamento di massa, in ordine di id). */
    public List<UUID> activeReservations(UUID eventId) {
        return store.reservationsOf(eventId, List.of("PENDING", "CONFIRMED"));
    }

    static BookableEvent view(EventStore.Row r) {
        return new BookableEvent(r.id(), r.slug(), r.title(), r.status(), r.bookingMode(), r.startsAt(), r.endsAt(), r.zoneId(),
                r.exclusive(), r.capacityCovers(), r.confirmedCovers());
    }
}
