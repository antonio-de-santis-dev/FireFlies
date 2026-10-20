package it.fireflies.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import it.fireflies.dining.Band;

/** Orari della pulizia notturna con l'ora legale (Europe/Rome) e fasce dall'orario. Nessun database. */
class RetentionJobTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static List<ZonedDateTime> firings(LocalDateTime from, int count) {
        CronExpression cron = CronExpression.parse(RetentionJob.CRON);
        List<ZonedDateTime> out = new ArrayList<>();
        ZonedDateTime t = from.atZone(ROME);
        for (int i = 0; i < count; i++) {
            t = cron.next(t);
            out.add(t);
        }
        return out;
    }

    @Test
    void giornoNormaleQuattroTentativiDalleDue() {
        assertThat(firings(LocalDateTime.of(2026, 10, 20, 12, 0), 4)).extracting(z -> z.toLocalTime().toString())
                .containsExactly("02:00", "03:00", "04:00", "05:00");
    }

    @Test
    void saltoInAvantiDelleDueSiPartealleTre() {
        // 29/3/2026: alle 02:00 gli orologi passano alle 03:00, le 02:00 non esistono
        List<ZonedDateTime> f = firings(LocalDateTime.of(2026, 3, 28, 23, 0), 3);
        assertThat(f.getFirst().toLocalDate().toString()).isEqualTo("2026-03-29");
        assertThat(f).extracting(z -> z.toLocalTime().getHour()).first().isIn(2, 3);
        assertThat(f).allMatch(z -> z.toLocalDate().toString().equals("2026-03-29"));
    }

    @Test
    void ritornoAllOraSolareStessoGiornoUnaSolaDataDiEsecuzione() {
        // 25/10/2026: le 02:00–02:59 capitano due volte; la data del giorno è sempre la stessa → una sola esecuzione
        List<ZonedDateTime> f = firings(LocalDateTime.of(2026, 10, 24, 23, 0), 5);
        assertThat(f).allMatch(z -> z.toLocalDate().toString().equals("2026-10-25"));
        assertThat(f.stream().map(ZonedDateTime::toLocalDate).distinct()).hasSize(1);
    }

    @Test
    void fasceDalNomeODallOrario() {
        assertThat(Band.of("Aperitivo", java.time.LocalTime.of(9, 0))).isEqualTo(Band.APERITIVO);
        assertThat(Band.of("  colazione ", java.time.LocalTime.of(18, 0))).isEqualTo(Band.COLAZIONE);
        assertThat(Band.of("Brunch", java.time.LocalTime.of(10, 30))).isEqualTo(Band.COLAZIONE);
        assertThat(Band.of(null, java.time.LocalTime.of(15, 0))).isEqualTo(Band.POMERIGGIO);
        assertThat(Band.of(null, java.time.LocalTime.of(19, 59))).isEqualTo(Band.APERITIVO);
        assertThat(Band.of(null, java.time.LocalTime.of(22, 30))).isEqualTo(Band.SERA);
        assertThat(Band.of(null, java.time.LocalTime.of(1, 0))).as("dopo mezzanotte").isEqualTo(Band.SERA);
    }
}
