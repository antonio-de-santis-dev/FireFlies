package it.fireflies.security;

import java.time.Clock;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Carica l'account per email: il ruolo arriva dal database, mai dalla richiesta di login. */
@Service
class GestoreDetailsService implements UserDetailsService {

    private final UserStore users;
    private final Clock clock;

    GestoreDetailsService(UserStore users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("account inesistente"));
        return new GestoreDetails(user, !user.lockedAt(clock.instant()));
    }
}
