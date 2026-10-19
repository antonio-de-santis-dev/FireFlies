package it.fireflies.common;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Orologio iniettabile: i test possono fissare il tempo (blocchi dopo tentativi, scadenze). */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
