package it.fireflies.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * ETag sulle letture pubbliche (API_CONTRACT §1): il browser rivalida con {@code If-None-Match} e riceve
 * {@code 304} senza corpo se nulla è cambiato. Dopo una modifica dei gestori l'ETag cambia subito.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PublicCacheConfiguration {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> publicEtags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/public/*");
        registration.setName("publicEtags");
        return registration;
    }
}
