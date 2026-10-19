package it.fireflies.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fireflies.security.*}: limiti dei tentativi di accesso (BOOKING_RULES §5, P-21).
 *
 * @param maxFailuresPerAccount tentativi falliti per email prima del blocco
 * @param maxFailuresPerIp      tentativi falliti per indirizzo IP prima del blocco
 * @param window                finestra in cui si contano i tentativi
 * @param lockDuration          durata del blocco dell'account
 */
@ConfigurationProperties("fireflies.security.login")
public record SecurityProperties(
        @DefaultValue("5") int maxFailuresPerAccount,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("15m") Duration window,
        @DefaultValue("15m") Duration lockDuration) {
}
