package it.fireflies.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;

import it.fireflies.common.ApiException;

/** Troppi tentativi di accesso: {@code 429} con {@code Retry-After}. Stessa risposta per email esistenti e inesistenti. */
public class TooManyAttemptsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(Duration wait) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Troppi tentativi",
                "Troppi tentativi di accesso. Riprova tra " + minutes(wait) + ".",
                Map.of("retryAfterSeconds", Math.max(1, wait.toSeconds())));
        this.retryAfterSeconds = Math.max(1, wait.toSeconds());
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String minutes(Duration wait) {
        long m = Math.max(1, (wait.toSeconds() + 59) / 60);
        return m == 1 ? "1 minuto" : m + " minuti";
    }
}
