package it.fireflies.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class BusinessTimeTest {

    @Test
    void oraLegaleEOraSolare() {
        assertThat(BusinessTime.toInstant(LocalDate.of(2030, 7, 1), LocalTime.of(19, 0)))
                .isEqualTo(Instant.parse("2030-07-01T17:00:00Z"));
        assertThat(BusinessTime.toInstant(LocalDate.of(2030, 1, 15), LocalTime.of(19, 0)))
                .isEqualTo(Instant.parse("2030-01-15T18:00:00Z"));
    }

    @Test
    void orarioInesistenteAlPassaggioAllOraLegaleRifiutato() {
        assertThatThrownBy(() -> BusinessTime.toInstant(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.code()).isEqualTo("INVALID_LOCAL_TIME");
                    assertThat(api.status().value()).isEqualTo(422);
                });
    }

    @Test
    void orarioAmbiguoAlRitornoAllOraSolareUsaLOraLegale() {
        assertThat(BusinessTime.toInstant(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30)))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z")); // +02:00
    }

    @Test
    void dataDiServizioLocale() {
        assertThat(BusinessTime.serviceDate(Instant.parse("2030-05-10T22:30:00Z"))).isEqualTo(LocalDate.of(2030, 5, 11));
    }
}
