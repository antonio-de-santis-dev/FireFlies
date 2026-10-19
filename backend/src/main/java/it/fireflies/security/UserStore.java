package it.fireflies.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL degli account dei gestori. Le email sono sempre normalizzate (minuscolo, senza spazi). */
@Repository
public class UserStore {

    private static final String COLUMNS = """
            id, email, password_hash, display_name, role, active, failed_login_count,
            locked_until, last_login_at, version
            """;

    private final JdbcClient jdbc;

    UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE email = ?")
                .param(normalizeEmail(email)).query(UserStore::user).optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE id = ?").param(id).query(UserStore::user).optional();
    }

    /** Lock della riga, per modifiche che devono vedere uno stato stabile. */
    Optional<AppUser> lockById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE id = ? FOR UPDATE").param(id).query(UserStore::user).optional();
    }

    public List<AppUser> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users ORDER BY role, lower(display_name)").query(UserStore::user).list();
    }

    /** Titolari attivi, bloccati in ordine di id: serializza i controlli «ultimo titolare». */
    List<UUID> lockActiveOwners() {
        return jdbc.sql("SELECT id FROM app_users WHERE role = 'OWNER' AND active ORDER BY id FOR UPDATE")
                .query(UUID.class).list();
    }

    boolean anyOwnerExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_users WHERE role = 'OWNER')").query(Boolean.class).single();
    }

    UUID insert(String email, String passwordHash, String displayName, Role role, Instant now) {
        return jdbc.sql("""
                INSERT INTO app_users (email, password_hash, display_name, role, password_changed_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """)
                .params(normalizeEmail(email), passwordHash, displayName.trim(), role.name(), utc(now))
                .query(UUID.class).single();
    }

    /** Aggiorna nome, ruolo e stato solo se la versione letta è ancora quella attuale. */
    boolean update(UUID id, int expectedVersion, String displayName, Role role, boolean active) {
        return jdbc.sql("""
                UPDATE app_users
                   SET display_name = ?, role = ?, active = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ?
                """)
                .params(displayName, role.name(), active, id, expectedVersion)
                .update() == 1;
    }

    /** Nuova password: azzera anche tentativi falliti e blocco. */
    void updatePassword(UUID id, String passwordHash, Instant now) {
        jdbc.sql("""
                UPDATE app_users
                   SET password_hash = ?, password_changed_at = ?, failed_login_count = 0, locked_until = NULL,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                """)
                .params(passwordHash, utc(now), id).update();
    }

    /**
     * Registra un tentativo fallito in modo atomico e, al raggiungimento della soglia, blocca l'account fino a
     * {@code lockUntil}. Restituisce il blocco risultante (vuoto se l'account non è bloccato).
     */
    Optional<Instant> recordFailure(UUID id, int threshold, Instant lockUntil) {
        return jdbc.sql("""
                UPDATE app_users
                   SET failed_login_count = failed_login_count + 1,
                       locked_until = CASE WHEN failed_login_count + 1 >= ? THEN ? ELSE locked_until END
                 WHERE id = ?
                RETURNING locked_until
                """)
                .params(threshold, utc(lockUntil), id)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)).map(OffsetDateTime::toInstant))
                .single();
    }

    void recordSuccess(UUID id, Instant now) {
        jdbc.sql("UPDATE app_users SET failed_login_count = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")
                .params(utc(now), id).update();
    }

    private static AppUser user(ResultSet rs, int row) throws SQLException {
        OffsetDateTime locked = rs.getObject("locked_until", OffsetDateTime.class);
        OffsetDateTime lastLogin = rs.getObject("last_login_at", OffsetDateTime.class);
        return new AppUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), Role.valueOf(rs.getString("role")), rs.getBoolean("active"),
                rs.getInt("failed_login_count"), locked == null ? null : locked.toInstant(),
                lastLogin == null ? null : lastLogin.toInstant(), rs.getInt("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
