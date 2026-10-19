package it.fireflies.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import it.fireflies.audit.AuditLog;
import it.fireflies.content.ContentService;
import it.fireflies.media.MediaService;
import it.fireflies.menu.MenuService;
import it.fireflies.support.PostgresIntegrationTest;
import tools.jackson.databind.ObjectMapper;

/**
 * Caricatore dei dati demo (profilo local) sulle foto vere del mockup: solo prezzi dimostrativi, allergeni da
 * dichiarare, database marcato come demo. Nel profilo dei test il caricatore non esiste come bean.
 */
class DemoContentLoaderIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MediaService media;

    @Autowired
    MenuService menu;

    @Autowired
    ContentService content;

    @Autowired
    ObjectMapper json;

    @Autowired
    AuditLog audit;

    @Test
    void nelProfiloTestIlCaricatoreNonEsiste() {
        assertThat(context.getBeanNamesForType(DemoContentLoader.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoDataGuard.class)).as("guardia solo con prod").isEmpty();
    }

    @Test
    void caricaIlMockupUnaSolaVoltaEMarcaIlDatabase() throws Exception {
        DemoContentLoader loader = new DemoContentLoader(new DemoProperties(true, "../frontend/public/assets/fireflies"),
                media, menu, content, jdbc, json, audit);
        loader.run(null);

        assertThat(jdbc.sql("SELECT count(*) FROM media_assets").query(Long.class).single()).isEqualTo(32);
        assertThat(jdbc.sql("SELECT count(*) FROM menu_items").query(Long.class).single()).isEqualTo(15);
        assertThat(jdbc.sql("SELECT count(*) FROM menu_items WHERE price_cents IS NOT NULL").query(Long.class).single())
                .as("nessun prezzo reale inventato").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM menu_items WHERE demo_price_cents IS NOT NULL").query(Long.class).single()).isEqualTo(15);
        assertThat(jdbc.sql("SELECT count(*) FROM menu_items WHERE allergens_status = 'DECLARED'").query(Long.class).single())
                .as("nessun allergene dichiarato a nome del locale").isZero();
        assertThat(content.hasDemoContent()).isTrue();
        assertThat(content.publicSite().content().owners()).hasSize(3);
        assertThat(content.publicSite().content().menuHero().photos()).hasSize(5);

        loader.run(null);
        assertThat(jdbc.sql("SELECT count(*) FROM media_assets").query(Long.class).single()).as("seconda volta: niente").isEqualTo(32);
        assertThatThrownBy(() -> DemoDataGuard.check(false, content.hasDemoContent())).isInstanceOf(IllegalStateException.class);
    }
}
