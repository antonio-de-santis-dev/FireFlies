package it.fireflies.security;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;

/**
 * Creazione del primo titolare: unico modo di far nascere un account senza essere già autenticati.
 * Funziona una sola volta: se esiste già un titolare si rifiuta. Nessuna credenziale è scritta nel codice.
 */
@Service
public class OwnerBootstrap {

    /** Chiave del lock consultivo PostgreSQL: due procedure lanciate insieme non creano due «primi» titolari. */
    private static final long BOOTSTRAP_LOCK = 0x46495245464C59L; // "FIREFLY"
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private final JdbcClient jdbc;
    private final UserStore users;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog audit;
    private final Clock clock;

    OwnerBootstrap(JdbcClient jdbc, UserStore users, PasswordEncoder passwordEncoder, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID createFirstOwner(String email, String displayName, String password) {
        String normalized = UserStore.normalizeEmail(email);
        if (!EMAIL.matcher(normalized).matches() || normalized.length() > 254) {
            throw new IllegalArgumentException("Email non valida: " + email);
        }
        if (displayName == null || displayName.trim().length() < 2 || displayName.trim().length() > 80) {
            throw new IllegalArgumentException("Il nome visualizzato deve avere tra 2 e 80 caratteri.");
        }
        String problem = PasswordPolicy.problem(password, normalized);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(BOOTSTRAP_LOCK).query().singleRow();
        if (users.anyOwnerExists()) {
            throw new IllegalStateException("Esiste già un titolare: questa procedura serve solo per il primo account. "
                    + "Gli altri account si creano dall'area gestori.");
        }
        UUID id = users.insert(normalized, passwordEncoder.encode(password), displayName, Role.OWNER, clock.instant());
        audit.record(id, AuditAction.OWNER_BOOTSTRAPPED, "USER", id.toString(), Map.of());
        return id;
    }
}
