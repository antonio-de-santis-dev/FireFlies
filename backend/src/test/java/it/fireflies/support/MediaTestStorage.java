package it.fireflies.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import it.fireflies.media.MediaStorage;

/** Sostituisce lo storage delle foto con {@link SwitchableMediaStorage} in tutti i test di integrazione. */
@TestConfiguration(proxyBeanMethods = false)
public class MediaTestStorage {

    @Bean
    @Primary
    SwitchableMediaStorage switchableMediaStorage(@Qualifier("mediaStorage") MediaStorage real) {
        return new SwitchableMediaStorage(real);
    }
}
