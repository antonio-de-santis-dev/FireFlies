package it.fireflies.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.fireflies.dining.DiningStore;

/** Regole pure delle prenotazioni: telefono, arrivi della fascia, arrotondamento al quanto, azioni consentite. */
class ReservationRulesTest {

    @Test
    void telefonoNormalizzatoInE164() {
        assertThat(PhoneNumbers.normalize("333 123 4567")).hasValue("+393331234567");
        assertThat(PhoneNumbers.normalize("+39 (0836) 12.34-56")).hasValue("+39083612345" + "6");
        assertThat(PhoneNumbers.normalize("0044 20 7946 0000")).hasValue("+442079460000");
        assertThat(PhoneNumbers.normalize("12")).isEmpty();
        assertThat(PhoneNumbers.normalize("chiamami")).isEmpty();
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
    }

    @Test
    void arriviDellaFascia() {
        DiningStore.WindowRow w = new DiningStore.WindowRow(UUID.randomUUID(), UUID.randomUUID(), 5, "Aperitivo",
                LocalTime.of(18, 0), LocalTime.of(20, 30), 30, 120, null, LocalDate.of(2026, 1, 1), null, true);
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(18, 0))).isTrue();
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(20, 30))).isTrue();
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(19, 15))).isFalse();
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(21, 0))).isFalse();
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(17, 30))).isFalse();
        assertThat(ReservationService.arrivalOf(w, LocalTime.of(19, 0, 30))).isFalse();
    }

    @Test
    void arrotondamentoAlQuantoSuccessivo() {
        Instant q = Instant.parse("2030-05-10T17:00:00Z");
        assertThat(ReservationService.ceilToQuantum(q)).isEqualTo(q);
        assertThat(ReservationService.ceilToQuantum(q.plusNanos(1))).isEqualTo(q.plus(Duration.ofMinutes(15)));
        assertThat(ReservationService.ceilToQuantum(q.plus(Duration.ofMinutes(14)))).isEqualTo(q.plus(Duration.ofMinutes(15)));
    }

    @Test
    void azioniSecondoStatoEOrario() {
        Instant start = Instant.parse("2030-05-10T17:00:00Z");
        assertThat(ReservationViews.actions(row("PENDING", start), start.minusSeconds(3600))).containsExactly("CONFIRM", "REJECT", "CANCEL", "EDIT");
        assertThat(ReservationViews.actions(row("PENDING", start), start)).doesNotContain("CONFIRM");
        assertThat(ReservationViews.actions(row("CONFIRMED", start), start.minusSeconds(60))).containsExactly("CANCEL", "EDIT", "TABLES");
        assertThat(ReservationViews.actions(row("CONFIRMED", start), start.plusSeconds(60))).containsExactly("COMPLETE", "EDIT", "TABLES");
        assertThat(ReservationViews.actions(row("CONFIRMED", start), start.plus(Duration.ofMinutes(15))))
                .containsExactly("COMPLETE", "NO_SHOW", "EDIT", "TABLES");
        assertThat(ReservationViews.actions(row("NO_SHOW", start), start.plus(Duration.ofHours(47)))).containsExactly("CORRECT", "NOTES");
        assertThat(ReservationViews.actions(row("NO_SHOW", start), start.plus(Duration.ofHours(49)))).containsExactly("NOTES");
        assertThat(ReservationViews.actions(row("REJECTED", start), start.minusSeconds(3600))).containsExactly("NOTES");
    }

    private static ReservationStore.Row row(String status, Instant start) {
        return new ReservationStore.Row(UUID.randomUUID(), "FF-ABCDEF", status, null, "WEB", LocalDate.of(2030, 5, 10), start,
                start.plus(Duration.ofHours(2)), null, 2, null, null, "Cliente", "+390000000000", null, null, null, false, null,
                null, null, null, null, null, start, start, 0, null);
    }
}
