package it.fireflies.security;

import java.time.Instant;
import java.util.UUID;

/** Account di un gestore come salvato in {@code app_users}. */
public record AppUser(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        Role role,
        boolean active,
        int failedLoginCount,
        Instant lockedUntil,
        Instant lastLoginAt,
        int version) {

    public boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
