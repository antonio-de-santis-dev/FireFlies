package it.fireflies.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Ruoli dell'area gestori. Il ruolo è salvato sull'account e assegnato solo dal backend (mai scelto al login). */
public enum Role {
    /** Titolare: tutti i permessi, compresi impostazioni, contenuti del sito e utenti. */
    OWNER,
    /** Staff: menu, eventi, richieste, foto, avvisi. */
    STAFF;

    /** Il titolare ha anche tutti i permessi dello staff. */
    public List<GrantedAuthority> authorities() {
        return this == OWNER
                ? List.of(new SimpleGrantedAuthority("ROLE_OWNER"), new SimpleGrantedAuthority("ROLE_STAFF"))
                : List.of(new SimpleGrantedAuthority("ROLE_STAFF"));
    }
}
