package it.fireflies.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hash delle password: bcrypt con costo 12 (P-16). Il prefisso {@code {bcrypt}} salvato con l'hash permette in
 * futuro di passare ad Argon2id senza migrare i dati: i vecchi hash restano verificabili.
 * Non dipende dal web: serve anche alla procedura di creazione del primo titolare.
 */
@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {

    static final int BCRYPT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
    }
}
