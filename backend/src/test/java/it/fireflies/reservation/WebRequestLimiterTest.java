package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** FIX-1: finestre scorrevoli dei limiti del modulo pubblico, senza Spring né database. */
class WebRequestLimiterTest {

    /** Orologio spostabile a mano. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2030-05-10T18:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

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

    private final TestClock clock = new TestClock();

    @Test
    void finestraScorrevoleConAttesaEsatta() {
        WebRequestLimiter.Window w = new WebRequestLimiter.Window(clock, Duration.ofHours(1), 3);
        assertThat(w.acquire("1.2.3.4")).isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(w.acquire("1.2.3.4")).isZero();
        assertThat(w.acquire("1.2.3.4")).isZero();
        assertThat(w.acquire("1.2.3.4")).as("il primo evento scade tra 50 minuti").isEqualTo(50 * 60);
        assertThat(w.acquire("5.6.7.8")).as("ogni indirizzo ha il suo limite").isZero();
        clock.advance(Duration.ofMinutes(50));
        assertThat(w.acquire("1.2.3.4")).as("uscito il primo, si rientra").isZero();
    }

    @Test
    void limitiSeparatiPerLettureTentativiERichieste() {
        WebRequestLimiter limiter = new WebRequestLimiter(clock, 2, 2, 1);
        assertThat(limiter.read("ip")).isZero();
        assertThat(limiter.read("ip")).isZero();
        assertThat(limiter.read("ip")).as("terza lettura nel minuto").isPositive();
        assertThat(limiter.attempt("ip")).as("le letture non toccano gli invii").isZero();
        assertThat(limiter.create("ip")).isZero();
        assertThat(limiter.create("ip")).isPositive();
        assertThat(limiter.attempt("ip")).isZero();
        assertThat(limiter.attempt("ip")).isPositive();
    }

    @Test
    void laMemoriaNonCresceConGliIndirizziVecchi() {
        WebRequestLimiter.Window w = new WebRequestLimiter.Window(clock, Duration.ofMinutes(1), 5);
        for (int i = 0; i < 999; i++) {
            w.acquire("10.0." + (i / 250) + "." + (i % 250));
        }
        assertThat(w.keys()).isEqualTo(999);
        clock.advance(Duration.ofMinutes(2));
        w.acquire("10.9.9.9"); // millesima chiamata: pulizia degli indirizzi senza eventi recenti
        assertThat(w.keys()).isLessThanOrEqualTo(1);
    }

    @Test
    void attesaLeggibileNeiMessaggi() {
        assertThat(ReservationService.waitText(20)).isEqualTo("tra un minuto");
        assertThat(ReservationService.waitText(60)).isEqualTo("tra un minuto");
        assertThat(ReservationService.waitText(61)).isEqualTo("tra 2 minuti");
        assertThat(ReservationService.waitText(50 * 60)).isEqualTo("tra 50 minuti");
        assertThat(ReservationService.waitText(3 * 3600)).isEqualTo("tra circa 3 ore");
    }
}
