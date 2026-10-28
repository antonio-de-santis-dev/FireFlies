package it.fireflies.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import tools.jackson.databind.ObjectMapper;

/**
 * Sceglie il gateway WhatsApp dalla modalità e verifica la configurazione <b>prima</b> di attivare quello reale:
 * <ul>
 * <li>DISABLED (predefinita): nessun controllo, nessuna rete;</li>
 * <li>FAKE e META_TEST: vietate con il profilo {@code prod} (protezione da un'attivazione accidentale in produzione);</li>
 * <li>META_TEST: servono token, Phone Number ID, versione della Graph API valida, almeno un numero di prova, nome e lingua
 * del modello validi e un indirizzo HTTPS (o di loopback nei test); altrimenti l'avvio si ferma con l'elenco di cosa manca,
 * senza mai stampare i valori;</li>
 * <li>PRODUCTION: riservata, oggi blocca sempre l'avvio.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class WhatsAppConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConfiguration.class);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Bean
    WhatsAppGateway whatsAppGateway(WhatsAppProperties config, Environment environment, ObjectMapper json) {
        validate(config, environment);
        WhatsAppGateway gateway = switch (config.mode()) {
            case DISABLED -> new DisabledWhatsAppGateway();
            case FAKE -> new FakeWhatsAppGateway();
            case META_TEST -> new MetaCloudWhatsAppGateway(config, HttpClient.newBuilder()
                    .connectTimeout(config.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER) // il token non segue mai un redirect
                    .build(), json);
            case PRODUCTION -> throw new IllegalStateException("unreachable");
        };
        log.info("WhatsApp: modalità {} ({})", config.mode(), config);
        return gateway;
    }

    static void validate(WhatsAppProperties config, Environment environment) {
        WhatsAppProperties.Mode mode = config.mode();
        if (mode == WhatsAppProperties.Mode.DISABLED) {
            return;
        }
        if (mode == WhatsAppProperties.Mode.PRODUCTION) {
            throw new IllegalStateException("FIREFLIES_WHATSAPP_MODE=PRODUCTION non è ancora disponibile: l'invio reale ai "
                    + "clienti richiede un'autorizzazione separata del titolare (WHATSAPP_CLOUD_API_REPORT.md §Produzione). "
                    + "Usa DISABLED, FAKE o META_TEST.");
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("FIREFLIES_WHATSAPP_MODE=" + mode + " non è ammessa con il profilo prod: "
                    + "in produzione WhatsApp resta DISABLED.");
        }
        if (mode == WhatsAppProperties.Mode.FAKE) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!config.hasAccessToken()) {
            missing.add("FIREFLIES_WHATSAPP_ACCESS_TOKEN mancante");
        } else if (config.accessToken().chars().anyMatch(ch -> ch <= ' ' || ch > '~')) {
            missing.add("FIREFLIES_WHATSAPP_ACCESS_TOKEN contiene spazi o caratteri non validi");
        }
        if (!config.hasPhoneNumberId() || !WhatsAppProperties.PHONE_NUMBER_ID.matcher(config.phoneNumberId()).matches()) {
            missing.add("FIREFLIES_WHATSAPP_PHONE_NUMBER_ID mancante o non numerico");
        }
        if (config.graphApiVersion() == null || !WhatsAppProperties.GRAPH_VERSION.matcher(config.graphApiVersion()).matches()) {
            missing.add("FIREFLIES_WHATSAPP_GRAPH_API_VERSION non valida (formato vNN.N)");
        }
        if (config.allowedRecipients().isEmpty()) {
            missing.add("FIREFLIES_WHATSAPP_TEST_RECIPIENTS vuota: in META_TEST serve almeno un numero di prova");
        } else if (config.allowedRecipients().stream().anyMatch(n -> n.length() < 8 || n.length() > 15)) {
            missing.add("FIREFLIES_WHATSAPP_TEST_RECIPIENTS contiene un numero non valido (formato E.164)");
        }
        if (config.templateName() == null || !WhatsAppProperties.TEMPLATE_NAME.matcher(config.templateName()).matches()) {
            missing.add("nome del modello non valido (solo minuscole, cifre e _)");
        }
        if (config.templateLanguage() == null || !WhatsAppProperties.LANGUAGE.matcher(config.templateLanguage()).matches()) {
            missing.add("lingua del modello non valida (es. it)");
        }
        URI base = config.graphBaseUrl();
        boolean https = base != null && "https".equals(base.getScheme());
        boolean loopback = base != null && "http".equals(base.getScheme()) && base.getHost() != null && LOOPBACK.contains(base.getHost());
        if (!https && !loopback) {
            missing.add("indirizzo della Graph API non HTTPS");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("WhatsApp META_TEST non attivabile: " + String.join("; ", missing)
                    + ". Nessun messaggio è partito. Vedi docs/operations/WHATSAPP_LOCAL_TEST_UBUNTU.md.");
        }
    }
}
