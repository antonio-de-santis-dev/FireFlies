package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    /** Orologio spostabile a mano. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2030-05-10T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(
            new SecurityProperties(5, 20, Duration.ofMinutes(15), Duration.ofMinutes(15)), clock);

    @Test
    void cinqueErroriBloccanoLEmailPerLaFinestra() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a@example.invalid", "10.0.0.1");
        }
        assertThat(limiter.blockedFor("a@example.invalid", "10.0.0.1")).isEmpty();
        limiter.recordFailure("a@example.invalid", "10.0.0.1");
        assertThat(limiter.blockedFor("a@example.invalid", "10.0.0.9")).contains(Duration.ofMinutes(15));

        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(limiter.blockedFor("a@example.invalid", "10.0.0.9")).contains(Duration.ofMinutes(5));
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(limiter.blockedFor("a@example.invalid", "10.0.0.9")).isEmpty();
    }

    @Test
    void altreEmailNonSonoBloccate() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("a@example.invalid", "10.0.0.1");
        }
        assertThat(limiter.blockedFor("b@example.invalid", "10.0.0.2")).isEmpty();
    }

    @Test
    void ventiErroriDaUnoStessoIpBloccanoTutteLeEmail() {
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("utente" + i + "@example.invalid", "10.0.0.1");
        }
        assertThat(limiter.blockedFor("nuovo@example.invalid", "10.0.0.1")).isPresent();
        assertThat(limiter.blockedFor("nuovo@example.invalid", "10.0.0.2")).isEmpty();
    }

    @Test
    void ilSuccessoAzzeraLEmail() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("a@example.invalid", "10.0.0.1");
        }
        limiter.recordSuccess("a@example.invalid");
        limiter.recordFailure("a@example.invalid", "10.0.0.1");
        assertThat(limiter.blockedFor("a@example.invalid", "10.0.0.1")).isEmpty();
    }
}
