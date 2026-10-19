package it.fireflies.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import it.fireflies.common.ApiException;
import it.fireflies.common.BusinessTime;

class OccupancyIntervalTest {

    private static Instant rome(String date, String time) {
        return BusinessTime.toInstant(LocalDate.parse(date), LocalTime.parse(time));
    }

    @Test
    void quantiDiDueOreSonoOtto() {
        OccupancyInterval interval = OccupancyInterval.of(rome("2030-05-10", "19:00"), Duration.ofHours(2));
        assertThat(interval.quanta()).hasSize(8)
                .startsWith(rome("2030-05-10", "19:00"))
                .endsWith(rome("2030-05-10", "20:45"));
    }

    @Test
    void lIntervalloEsclusoLaFine() {
        OccupancyInterval interval = OccupancyInterval.of(rome("2030-05-10", "19:00"), Duration.ofHours(2));
        assertThat(interval.contains(rome("2030-05-10", "20:45"))).isTrue();
        assertThat(interval.contains(rome("2030-05-10", "21:00"))).isFalse();
    }

    @Test
    void unEventoChePassaLaMezzanotteHaIQuantiDiDueGiorni() {
        OccupancyInterval interval = new OccupancyInterval(rome("2030-05-10", "22:00"), rome("2030-05-11", "02:00"));
        assertThat(interval.quanta()).hasSize(16);
    }

    @Test
    void aCavalloDelRitornoAllOraSolareContaLaDurataReale() {
        // 25/10/2026: alle 03:00 locali si torna alle 02:00 → dalle 01:00 alle 03:00 locali passano 3 ore reali.
        OccupancyInterval interval = new OccupancyInterval(rome("2026-10-25", "01:00"), rome("2026-10-25", "03:00"));
        assertThat(Duration.between(interval.start(), interval.end())).isEqualTo(Duration.ofHours(3));
        assertThat(interval.quanta()).hasSize(12);
    }

    @Test
    void orarioNonAllineatoRifiutato() {
        assertThatThrownBy(() -> OccupancyInterval.of(rome("2030-05-10", "19:10"), Duration.ofHours(2)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("NOT_ALIGNED_15_MIN");
    }

    @Test
    void durataNullaONegativaOOltre24OreRifiutata() {
        Instant start = rome("2030-05-10", "19:00");
        assertThatThrownBy(() -> new OccupancyInterval(start, start))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_DURATION");
        assertThatThrownBy(() -> OccupancyInterval.of(start, Duration.ofHours(25)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_DURATION");
    }
}
