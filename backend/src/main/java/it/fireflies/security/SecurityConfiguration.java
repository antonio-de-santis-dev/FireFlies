package it.fireflies.security;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import tools.jackson.databind.ObjectMapper;

/**
 * Sicurezza dell'API (ADR-0003): sessione server-side in PostgreSQL, cookie {@code HttpOnly}, CSRF con cookie
 * {@code XSRF-TOKEN} + header {@code X-XSRF-TOKEN}, ruoli verificati su ogni richiesta.
 * Il link nascosto dell'area gestori non è una protezione: lo sono queste regole.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    static final String SESSION_COOKIE = "FF_SESSION";

    private static final String[] CONFIG_PATHS = {
            "/api/v1/admin/capacity-zones/**", "/api/v1/admin/areas/**", "/api/v1/admin/tables/**",
            "/api/v1/admin/service-windows/**", "/api/v1/admin/booking-policy", "/api/v1/admin/booking-exceptions/**",
            "/api/v1/admin/capacity-matrix", "/api/v1/admin/opening-exceptions/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblemHandlers problems, LogoutHandler auditingLogout,
            SecurityContextRepository contextRepository) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/reservations").permitAll() // con CSRF e Idempotency-Key
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll() // varianti delle foto (solo storage locale)
                        .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasRole("OWNER")
                        .requestMatchers("/api/v1/admin/site-content", "/api/v1/admin/site-settings").hasRole("OWNER")
                        // configurazione delle prenotazioni: la legge anche lo staff, la cambia solo il titolare
                        .requestMatchers(HttpMethod.POST, CONFIG_PATHS).hasRole("OWNER")
                        .requestMatchers(HttpMethod.PUT, CONFIG_PATHS).hasRole("OWNER")
                        .requestMatchers(HttpMethod.DELETE, CONFIG_PATHS).hasRole("OWNER")
                        .requestMatchers("/api/v1/admin/**").hasRole("STAFF")
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.spa())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler(auditingLogout)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies(SESSION_COOKIE))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    SecurityProblemHandlers securityProblemHandlers(ObjectMapper json, AuditLog audit) {
        return new SecurityProblemHandlers(json, audit);
    }

    @Bean
    LogoutHandler auditingLogout(AuditLog audit) {
        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof GestoreDetails gestore) {
                audit.record(gestore.id(), AuditAction.LOGOUT, "USER", gestore.id().toString(), Map.of());
            }
        };
    }

    @Bean
    AuthenticationManager authenticationManager(GestoreDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Stesso cookie configurato da {@code csrf.spa()}: serve a rigenerare il token dopo il login. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /** Al login: nuovo id di sessione (anti session fixation) e nuovo token CSRF. */
    @Bean
    SessionAuthenticationStrategy loginSessionStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
    }

    static GestoreDetails gestore(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof GestoreDetails g ? g : null;
    }
}
