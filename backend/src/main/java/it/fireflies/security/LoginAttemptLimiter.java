package it.fireflies.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Limita i tentativi di accesso falliti per email e per indirizzo IP in una finestra mobile.
 * Vale anche per email inesistenti, così la risposta non rivela se un account esiste.
 * In memoria: adatto a una sola istanza del backend (V1); il blocco dell'account in database resta anche dopo un riavvio.
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final SecurityProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    LoginAttemptLimiter(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Tempo da attendere se email o IP hanno superato il limite; vuoto se si può tentare. */
    public Optional<Duration> blockedFor(String email, String ip) {
        Instant now = clock.instant();
        Optional<Duration> byEmail = wait(key("email", email), properties.maxFailuresPerAccount(), now);
        Optional<Duration> byIp = wait(key("ip", ip), properties.maxFailuresPerIp(), now);
        if (byEmail.isPresent() && byIp.isPresent()) {
            return Optional.of(byEmail.get().compareTo(byIp.get()) >= 0 ? byEmail.get() : byIp.get());
        }
        return byEmail.isPresent() ? byEmail : byIp;
    }

    public void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        if (failures.size() > MAX_TRACKED_KEYS) {
            failures.entrySet().removeIf(e -> prune(e.getValue(), now).isEmpty());
        }
        add(key("email", email), now);
        add(key("ip", ip), now);
    }

    /** Dopo un accesso riuscito i tentativi dell'email ripartono da zero (quelli dell'IP no). */
    public void recordSuccess(String email) {
        failures.remove(key("email", email));
    }

    /** Svuota tutti i contatori (test automatici). */
    public void clear() {
        failures.clear();
    }

    private Optional<Duration> wait(String key, int max, Instant now) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return Optional.empty();
        }
        synchronized (attempts) {
            prune(attempts, now);
            if (attempts.size() < max) {
                return Optional.empty();
            }
            Instant freeAt = attempts.peekFirst().plus(properties.window());
            return Optional.of(Duration.between(now, freeAt));
        }
    }

    private void add(String key, Instant now) {
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts, now);
            attempts.addLast(now);
        }
    }

    private Deque<Instant> prune(Deque<Instant> attempts, Instant now) {
        synchronized (attempts) {
            Instant limit = now.minus(properties.window());
            while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(limit)) {
                attempts.removeFirst();
            }
            return attempts;
        }
    }

    private static String key(String kind, String value) {
        return kind + ":" + (value == null ? "" : value);
    }
}
