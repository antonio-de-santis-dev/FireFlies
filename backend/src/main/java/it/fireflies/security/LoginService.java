package it.fireflies.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiException;

/**
 * Accesso dei gestori. Il ruolo arriva dall'account sul database; la richiesta contiene solo email e password.
 * Risposte uniformi: email inesistente, password sbagliata e account disattivato danno lo stesso {@code 401}.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final LoginAttemptLimiter limiter;
    private final UserStore users;
    private final AuditLog audit;
    private final SecurityProperties properties;
    private final Clock clock;

    LoginService(AuthenticationManager authenticationManager, SessionAuthenticationStrategy loginSessionStrategy,
            SecurityContextRepository contextRepository, LoginAttemptLimiter limiter, UserStore users, AuditLog audit,
            SecurityProperties properties, Clock clock) {
        this.authenticationManager = authenticationManager;
        this.sessionStrategy = loginSessionStrategy;
        this.contextRepository = contextRepository;
        this.limiter = limiter;
        this.users = users;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    GestoreDetails login(String rawEmail, String password, HttpServletRequest request, HttpServletResponse response) {
        String email = UserStore.normalizeEmail(rawEmail);
        String ip = request.getRemoteAddr();

        Optional<Duration> blocked = limiter.blockedFor(email, ip);
        if (blocked.isPresent()) {
            users.findByEmail(email).ifPresent(u -> audit.record(u.id(), AuditAction.LOGIN_BLOCKED, "USER",
                    u.id().toString(), Map.of("ip", ip)));
            throw new TooManyAttemptsException(blocked.get());
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            GestoreDetails gestore = (GestoreDetails) authentication.getPrincipal();

            sessionStrategy.onAuthentication(authentication, request, response);
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);

            users.recordSuccess(gestore.id(), clock.instant());
            limiter.recordSuccess(email);
            audit.record(gestore.id(), AuditAction.LOGIN_SUCCEEDED, "USER", gestore.id().toString(), Map.of("ip", ip));
            return gestore;
        } catch (LockedException e) {
            AppUser user = users.findByEmail(email).orElseThrow();
            audit.record(user.id(), AuditAction.LOGIN_BLOCKED, "USER", user.id().toString(), Map.of("ip", ip));
            Instant until = user.lockedUntil() == null ? clock.instant().plus(properties.lockDuration()) : user.lockedUntil();
            throw new TooManyAttemptsException(Duration.between(clock.instant(), until));
        } catch (BadCredentialsException | DisabledException e) {
            recordFailure(email, ip, e instanceof DisabledException ? "DISABLED" : "BAD_CREDENTIALS");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Accesso non riuscito",
                    "Email o password non corretti.");
        }
    }

    private void recordFailure(String email, String ip, String reason) {
        limiter.recordFailure(email, ip);
        Optional<AppUser> user = users.findByEmail(email);
        if (user.isEmpty()) {
            // Nessun dato sull'email tentata: potrebbe essere un errore di battitura con dentro una password.
            audit.record(null, AuditAction.LOGIN_FAILED, "AUTH", "unknown-account", Map.of("ip", ip, "reason", reason));
            return;
        }
        AppUser account = user.get();
        Instant lockUntil = clock.instant().plus(properties.lockDuration());
        Optional<Instant> locked = users.recordFailure(account.id(), properties.maxFailuresPerAccount(), clock.instant(),
                properties.window(), lockUntil);
        audit.record(account.id(), AuditAction.LOGIN_FAILED, "USER", account.id().toString(), Map.of("ip", ip, "reason", reason));
        if (locked.isPresent() && !account.lockedAt(clock.instant())) {
            log.warn("Account {} bloccato fino a {} dopo troppi tentativi", account.id(), locked.get());
            audit.record(account.id(), AuditAction.ACCOUNT_LOCKED, "USER", account.id().toString(),
                    Map.of("until", locked.get().toString(), "ip", ip));
        }
    }
}
