package it.fireflies.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fireflies.notifications.*}: quale provider usare per canale. L'unico valore ammesso in V1 è {@code fake}.
 */
@ConfigurationProperties("fireflies.notifications")
public record NotificationProperties(@DefaultValue Channel sms, @DefaultValue Channel email) {

    public record Channel(@DefaultValue("fake") String provider) {

        public boolean isFake() {
            return "fake".equalsIgnoreCase(provider);
        }
    }
}
