package it.fireflies.capacity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CapacityConfiguration {

    /** Finché non esistono fasce configurabili (FASE 5) la capienza di ogni quanto è quella fisica della zona. */
    @Bean
    @ConditionalOnMissingBean
    SlotCapacityPolicy physicalCapacityOnly() {
        return (zoneId, physicalCapacity, slotStart) -> physicalCapacity;
    }
}
