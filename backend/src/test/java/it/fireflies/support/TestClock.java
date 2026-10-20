package it.fireflies.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Orologio dei test: scorre come quello reale, ma si può spostare avanti (scadenze, «non presentato», correzioni
 * entro 48 ore). Dopo ogni test torna all'ora reale.
 */
public final class TestClock extends Clock {

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        TestClock clock() {
            return new TestClock();
        }
    }

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(Clock.system(zone), offset.get());
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }
}
