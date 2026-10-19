package it.fireflies.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fireflies.demo.*}
 *
 * @param seed      carica i contenuti demo al primo avvio su un database vuoto (solo profilo {@code local})
 * @param assetsDir cartella delle foto del locale da importare (quelle del mockup approvato)
 */
@ConfigurationProperties("fireflies.demo")
public record DemoProperties(@DefaultValue("false") boolean seed,
        @DefaultValue("../frontend/public/assets/fireflies") String assetsDir) {
}
