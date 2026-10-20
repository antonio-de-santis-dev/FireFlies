package it.fireflies.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limite delle richieste dal sito per indirizzo IP (BOOKING_RULES §5: 5 all'ora). In memoria, come il limitatore
 * dei login (DECISION_LOG T-17): si azzera al riavvio e non si condivide tra più istanze; il limite per telefono
 * (3 al giorno) invece si conta sul database e resiste ai riavvii.
 */
@Component
public class WebRequestLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> byIp = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxPerHour;

    /** {@code fireflies.reservations.web-requests-per-hour}: 5 come da regole; più alto solo nei test end-to-end. */
    WebRequestLimiter(Clock clock, @Value("${fireflies.reservations.web-requests-per-hour:5}") int maxPerHour) {
        this.clock = clock;
        this.maxPerHour = maxPerHour;
    }

    /** Secondi da attendere, oppure 0 se la richiesta è ammessa (e viene contata). */
    long acquire(String ip) {
        Instant now = Instant.now(clock);
        Deque<Instant> hits = byIp.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && !hits.peekFirst().isAfter(now.minus(WINDOW))) {
                hits.pollFirst();
            }
            if (hits.size() >= maxPerHour) {
                return Math.max(1, Duration.between(now, hits.peekFirst().plus(WINDOW)).toSeconds());
            }
            hits.addLast(now);
            return 0;
        }
    }

    public void clear() {
        byIp.clear();
    }
}
