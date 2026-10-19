package it.fireflies.security;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiException;

/**
 * Account dei gestori: creazione e modifica da parte del titolare, cambio della propria password.
 * I ruoli si assegnano solo qui, da un titolare autenticato; il login non li riceve mai.
 */
@Service
public class UserAccountService {

    public record NewUser(String email, String displayName, Role role, String password) {
    }

    public record UserChange(int version, String displayName, Role role, Boolean active) {
    }

    private final UserStore users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevoker sessions;
    private final AuditLog audit;
    private final Clock clock;

    UserAccountService(UserStore users, PasswordEncoder passwordEncoder, SessionRevoker sessions, AuditLog audit, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() {
        return users.findAll();
    }

    @Transactional
    public AppUser create(UUID actorId, NewUser request) {
        PasswordPolicy.check(request.password(), request.email());
        UUID id = users.insert(request.email(), passwordEncoder.encode(request.password()), request.displayName(),
                request.role(), clock.instant());
        audit.record(actorId, AuditAction.USER_CREATED, "USER", id.toString(), Map.of("role", request.role().name()));
        return users.findById(id).orElseThrow();
    }

    /**
     * Cambia nome, ruolo o stato. Non si può togliere il ruolo o disattivare l'ultimo titolare attivo.
     * Se cambiano ruolo o stato, tutte le sessioni dell'account vengono chiuse.
     */
    @Transactional
    public AppUser update(UUID actorId, UUID userId, UserChange change) {
        AppUser current = users.lockById(userId).orElseThrow(UserAccountService::notFound);
        if (current.version() != change.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Dati cambiati nel frattempo",
                    "Qualcun altro ha modificato questo account: ricarica e riprova.");
        }
        String displayName = change.displayName() == null ? current.displayName() : change.displayName().trim();
        Role role = change.role() == null ? current.role() : change.role();
        boolean active = change.active() == null ? current.active() : change.active();

        boolean losesOwner = current.role() == Role.OWNER && current.active() && (role != Role.OWNER || !active);
        if (losesOwner && users.lockActiveOwners().size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_OWNER", "Ultimo titolare",
                    "Deve restare almeno un titolare attivo: nomina prima un altro titolare.");
        }
        users.update(userId, current.version(), displayName, role, active);

        Map<String, Object> changes = new LinkedHashMap<>();
        if (role != current.role()) {
            changes.put("role", current.role() + "→" + role);
        }
        if (active != current.active()) {
            changes.put("active", current.active() + "→" + active);
        }
        if (!displayName.equals(current.displayName())) {
            changes.put("displayName", "modificato");
        }
        if (changes.containsKey("role") || changes.containsKey("active")) {
            changes.put("sessionsClosed", sessions.revokeAll(userId, null));
        }
        audit.record(actorId, AuditAction.USER_UPDATED, "USER", userId.toString(), changes);
        return users.findById(userId).orElseThrow();
    }

    /** Il titolare imposta una nuova password per un account: sblocca l'account e chiude le sue sessioni. */
    @Transactional
    public void resetPassword(UUID actorId, UUID userId, String newPassword) {
        AppUser user = users.lockById(userId).orElseThrow(UserAccountService::notFound);
        PasswordPolicy.check(newPassword, user.email());
        users.updatePassword(userId, passwordEncoder.encode(newPassword), clock.instant());
        int closed = sessions.revokeAll(userId, null);
        audit.record(actorId, AuditAction.USER_PASSWORD_RESET, "USER", userId.toString(), Map.of("sessionsClosed", closed));
    }

    /** Cambio della propria password: richiede quella attuale; chiude le altre sessioni dell'account. */
    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword, String currentSessionId) {
        AppUser user = users.lockById(userId).orElseThrow(UserAccountService::notFound);
        if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CURRENT_PASSWORD_WRONG", "Password attuale errata",
                    "La password attuale non è corretta.");
        }
        PasswordPolicy.check(newPassword, user.email());
        users.updatePassword(userId, passwordEncoder.encode(newPassword), clock.instant());
        int closed = sessions.revokeAll(userId, currentSessionId);
        audit.record(userId, AuditAction.PASSWORD_CHANGED, "USER", userId.toString(), Map.of("otherSessionsClosed", closed));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account non trovato", "L'account indicato non esiste.");
    }
}
