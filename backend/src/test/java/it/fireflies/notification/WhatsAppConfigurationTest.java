package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Modalità e controlli all'avvio: DISABLED predefinita, PRODUCTION riservata, FAKE e META_TEST vietate in prod,
 * META_TEST solo con configurazione completa; nessun segreto nei messaggi d'errore né in {@code toString()}.
 */
class WhatsAppConfigurationTest {

    static final String TOKEN = "TOKEN-DI-PROVA-NON-REALE";
    static final String SECRET = "APP-SECRET-DI-PROVA";

    static WhatsAppProperties props(WhatsAppProperties.Mode mode, String token, String phoneId, String version, String recipients,
            String base) {
        return new WhatsAppProperties(mode, token, phoneId, "999", version, recipients, "VERIFY-DI-PROVA", SECRET,
                URI.create(base), "fireflies_prenotazione_confermata", "it", Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    static MockEnvironment profile(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void predefinitaDisattivataSenzaControlli() {
        WhatsAppProperties none = new WhatsAppProperties(WhatsAppProperties.Mode.DISABLED, null, null, null, "v21.0", "", null, null,
                URI.create("https://graph.facebook.com"), "fireflies_prenotazione_confermata", "it", Duration.ofSeconds(5), Duration.ofSeconds(10));
        WhatsAppConfiguration.validate(none, profile("prod"));
        assertThat(none.webhookEnabled()).isFalse();
        assertThat(none.allowedRecipients()).isEmpty();
    }

    @Test
    void produzioneRiservataEModalitaDiProvaVietateInProd() {
        assertThatThrownBy(() -> WhatsAppConfiguration.validate(
                props(WhatsAppProperties.Mode.PRODUCTION, TOKEN, "123456", "v21.0", "+390000000001", "https://graph.facebook.com"), profile("local")))
                .hasMessageContaining("PRODUCTION non è ancora disponibile");
        for (WhatsAppProperties.Mode m : List.of(WhatsAppProperties.Mode.FAKE, WhatsAppProperties.Mode.META_TEST)) {
            assertThatThrownBy(() -> WhatsAppConfiguration.validate(
                    props(m, TOKEN, "123456", "v21.0", "+390000000001", "https://graph.facebook.com"), profile("prod")))
                    .hasMessageContaining("non è ammessa con il profilo prod");
        }
        WhatsAppConfiguration.validate(props(WhatsAppProperties.Mode.FAKE, null, null, null, "", "https://x"), profile("local"));
    }

    @Test
    void metaTestRichiedeConfigurazioneCompletaSenzaMostrareIValori() {
        assertThatThrownBy(() -> WhatsAppConfiguration.validate(
                props(WhatsAppProperties.Mode.META_TEST, null, "abc", "21", "", "http://example.org"), profile("local")))
                .hasMessageContaining("FIREFLIES_WHATSAPP_ACCESS_TOKEN mancante")
                .hasMessageContaining("PHONE_NUMBER_ID")
                .hasMessageContaining("GRAPH_API_VERSION")
                .hasMessageContaining("TEST_RECIPIENTS vuota")
                .hasMessageContaining("non HTTPS");
        assertThatThrownBy(() -> WhatsAppConfiguration.validate(
                props(WhatsAppProperties.Mode.META_TEST, TOKEN + " x", "123456", "v21.0", "123", "https://graph.facebook.com"), profile("local")))
                .hasMessageContaining("caratteri non validi").hasMessageContaining("numero non valido")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain(SECRET));
        WhatsAppConfiguration.validate(
                props(WhatsAppProperties.Mode.META_TEST, TOKEN, "123456", "v21.0", "+390000000001", "https://graph.facebook.com"), profile("local"));
        WhatsAppConfiguration.validate(
                props(WhatsAppProperties.Mode.META_TEST, TOKEN, "123456", "v21.0", "+390000000001", "http://127.0.0.1:9999"), profile("test"));
    }

    @Test
    void toStringSenzaSegretiNeNumeri() {
        WhatsAppProperties p = props(WhatsAppProperties.Mode.META_TEST, TOKEN, "123456", "v21.0", "+390000000001;\n+39 000 000 0002",
                "https://graph.facebook.com");
        assertThat(p.toString()).doesNotContain(TOKEN).doesNotContain(SECRET).doesNotContain("VERIFY-DI-PROVA")
                .doesNotContain("123456").doesNotContain("390000000001").contains("testRecipients=2");
        assertThat(p.allowedRecipients()).containsExactly("390000000001", "390000000002");
    }

    @Test
    void parametriDelModelloNormalizzatiOBloccati() {
        assertThat(NotificationDispatcher.confirmationParameters(Map.of("nome", "  Anna \n Maria\t", "data", "3/11/2026", "ora", "19:00",
                "persone", "4"))).contains(List.of("Anna Maria", "3/11/2026", "19:00", "4"));
        assertThat(NotificationDispatcher.confirmationParameters(Map.of("nome", " ", "data", "3/11/2026", "ora", "19:00", "persone", "4")))
                .isEmpty();
        assertThat(NotificationDispatcher.confirmationParameters(Map.of("data", "3/11/2026"))).isEmpty();
    }

    @Test
    void statiDiConsegnaSoloInAvanti() {
        assertThat(WhatsAppWebhookService.advances("", "SENT")).isTrue();
        assertThat(WhatsAppWebhookService.advances("SENT", "DELIVERED")).isTrue();
        assertThat(WhatsAppWebhookService.advances("DELIVERED", "READ")).isTrue();
        assertThat(WhatsAppWebhookService.advances("", "READ")).as("read prima di sent").isTrue();
        assertThat(WhatsAppWebhookService.advances("DELIVERED", "SENT")).isFalse();
        assertThat(WhatsAppWebhookService.advances("READ", "DELIVERED")).isFalse();
        assertThat(WhatsAppWebhookService.advances("SENT", "FAILED")).isTrue();
        assertThat(WhatsAppWebhookService.advances("DELIVERED", "FAILED")).isFalse();
        assertThat(WhatsAppWebhookService.advances("FAILED", "READ")).isFalse();
    }
}
