package it.fireflies.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Gestore autenticato, salvato nella sessione server-side (serializzabile).
 * {@link #getUsername()} restituisce l'<b>id</b> dell'account: è il «principal name» con cui Spring Session
 * indicizza le sessioni, così si possono chiudere tutte quelle di un utente anche se cambia email.
 */
public final class GestoreDetails implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final Role role;
    private final boolean enabled;
    private final boolean nonLocked;
    private String passwordHash;

    GestoreDetails(AppUser user, boolean nonLocked) {
        this.id = user.id();
        this.email = user.email();
        this.displayName = user.displayName();
        this.role = user.role();
        this.enabled = user.active();
        this.nonLocked = nonLocked;
        this.passwordHash = user.passwordHash();
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    @Override
    public String getUsername() {
        return id.toString();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return role.authorities();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonLocked() {
        return nonLocked;
    }

    /** L'hash della password non resta in sessione. */
    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
