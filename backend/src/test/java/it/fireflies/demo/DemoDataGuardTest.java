package it.fireflies.demo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** In produzione i dati demo bloccano l'avvio, sia richiesti sia già presenti nel database. */
class DemoDataGuardTest {

    @Test
    void databasePulitoSenzaCaricamentoParte() {
        assertThatCode(() -> DemoDataGuard.check(false, false)).doesNotThrowAnyException();
    }

    @Test
    void caricamentoDemoRichiestoInProduzioneBloccato() {
        assertThatThrownBy(() -> DemoDataGuard.check(true, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREFLIES_DEMO_SEED");
    }

    @Test
    void databaseConDatiDemoBloccato() {
        assertThatThrownBy(() -> DemoDataGuard.check(false, true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIMOSTRATIVI");
    }
}
