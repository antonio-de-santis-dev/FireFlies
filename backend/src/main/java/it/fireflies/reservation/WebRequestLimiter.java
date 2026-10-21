package it.fireflies.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limiti per indirizzo IP del modulo pubblico (BOOKING_RULES §5.1, FIX-1). Tre contatori distinti, in memoria come il
 * limitatore dei login (T-17: si azzerano al riavvio, una sola istanza del backend):
 * <ul>
 * <li><b>letture della disponibilità</b> ({@code booking-options}): molto larghe, servono solo contro chi interroga in
 * continuazione; il modulo ne fa una a ogni cambio di giorno o di persone;</li>
 * <li><b>tentativi di invio</b>: ogni {@code POST}, anche se poi rifiutato per dati non validi o orario pieno;
 * fermano chi prova a raffica senza toccare una persona che corregge il modulo qualche volta;</li>
 * <li><b>richieste create</b>: contate <b>solo</b> quando la richiesta viene davvero salvata. Fino alla FASE 8 il
 * contatore (5 all'ora) scattava prima dei controlli, quindi anche gli invii rifiutati lo consumavano e un cliente
 * poteva restare bloccato senza aver inviato nulla.</li>
 * </ul>
 * Il limite per telefono (3 richieste in 24 ore) si conta sul database e resiste ai riavvii. I reinvii con la stessa
 * {@code Idempotency-Key} non contano mai: restituiscono la stessa ricevuta.
 */
@Component
public class WebRequestLimiter {

    private final Window reads;
    private final Window attempts;
    private final Window created;

    /** Valori più alti solo nei test end-to-end, dove tutte le richieste arrivano da 127.0.0.1. */
    WebRequestLimiter(Clock clock,
            @Value("${fireflies.reservations.availability-reads-per-minute:120}") int readsPerMinute,
            @Value("${fireflies.reservations.web-attempts-per-10-minutes:30}") int attemptsPer10Minutes,
            @Value("${fireflies.reservations.web-requests-per-hour:10}") int createdPerHour) {
        this.reads = new Window(clock, Duration.ofMinutes(1), readsPerMinute);
        this.attempts = new Window(clock, Duration.ofMinutes(10), attemptsPer10Minutes);
        this.created = new Window(clock, Duration.ofHours(1), createdPerHour);
    }

    /** Lettura degli orari disponibili: secondi da attendere, oppure 0 (e la lettura viene contata). */
    public long read(String ip) {
        return reads.acquire(ip);
    }

    /** Tentativo di invio (qualunque esito): secondi da attendere, oppure 0. */
    long attempt(String ip) {
        return attempts.acquire(ip);
    }

    /** Richiesta che sta per essere salvata: secondi da attendere, oppure 0 (e viene contata). */
    long create(String ip) {
        return created.acquire(ip);
    }

    public void clear() {
        reads.clear();
        attempts.clear();
        created.clear();
    }

    /** Finestra scorrevole per chiave: al massimo {@code max} eventi negli ultimi {@code length}. */
    static final class Window {

        private static final int PURGE_EVERY = 1_000;

        private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final Clock clock;
        private final Duration length;
        private final int max;

        Window(Clock clock, Duration length, int max) {
            this.clock = clock;
            this.length = length;
            this.max = max;
        }

        long acquire(String key) {
            Instant now = Instant.now(clock);
            if (calls.incrementAndGet() % PURGE_EVERY == 0) {
                purge(now);
            }
            Deque<Instant> deque = hits.computeIfAbsent(key == null ? "?" : key, k -> new ArrayDeque<>());
            synchronized (deque) {
                drop(deque, now);
                if (deque.size() >= max) {
                    return Math.max(1, Duration.between(now, deque.peekFirst().plus(length)).toSeconds());
                }
                deque.addLast(now);
                return 0;
            }
        }

        /** Toglie gli indirizzi senza eventi recenti: la memoria non cresce con il numero di visitatori. */
        private void purge(Instant now) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    drop(e.getValue(), now);
                    return e.getValue().isEmpty();
                }
            });
        }

        private void drop(Deque<Instant> deque, Instant now) {
            while (!deque.isEmpty() && !deque.peekFirst().isAfter(now.minus(length))) {
                deque.pollFirst();
            }
        }

        void clear() {
            hits.clear();
        }

        int keys() {
            return hits.size();
        }
    }
}
