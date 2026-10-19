package it.fireflies.notification;

import java.util.Arrays;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Sceglie i gateway di invio e impedisce in modo esplicito qualunque provider reale (ADR-0005):
 * <ul>
 *   <li>con profilo {@code local} o {@code test} un provider diverso da {@code fake} blocca l'avvio;</li>
 *   <li>in V1 non esiste alcun adattatore reale: qualunque altro valore blocca l'avvio anche in {@code prod}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class NotificationGatewaysConfiguration {

    private static final Set<String> SAFE_PROFILES = Set.of("local", "test");

    @Bean
    SmsGateway smsGateway(NotificationProperties properties, Environment environment) {
        requireFake("SMS", properties.sms(), environment);
        return new FakeSmsGateway();
    }

    @Bean
    EmailGateway emailGateway(NotificationProperties properties, Environment environment) {
        requireFake("email", properties.email(), environment);
        return new FakeEmailGateway();
    }

    static void requireFake(String channel, NotificationProperties.Channel config, Environment environment) {
        if (config.isFake()) {
            return;
        }
        boolean safeProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch(SAFE_PROFILES::contains);
        if (safeProfile) {
            throw new IllegalStateException("Provider " + channel + " «" + config.provider()
                    + "» configurato con profilo " + String.join(",", environment.getActiveProfiles())
                    + ": in sviluppo e test è ammesso solo 'fake'. Nessun messaggio reale può partire da qui.");
        }
        throw new IllegalStateException("Provider " + channel + " «" + config.provider()
                + "» non disponibile: in V1 esiste solo 'fake'. L'adattatore reale si aggiunge in FASE 7 "
                + "dopo l'autorizzazione dei costi del titolare.");
    }
}
