package it.fireflies.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import it.fireflies.security.AppUser;
import it.fireflies.security.Role;
import it.fireflies.security.UserAccountService;
import tools.jackson.databind.ObjectMapper;

/** Base dei test di sicurezza: server HTTP reale, account creati con password casuali (nessuna credenziale fissa). */
public abstract class SecurityIntegrationTest extends PostgresIntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected UserAccountService accounts;

    public record TestUser(AppUser account, String password) {
        public String email() {
            return account.email();
        }
    }

    protected ApiClient browser() {
        return new ApiClient(port, json);
    }

    protected TestUser user(String email, Role role) {
        String password = randomPassword();
        AppUser account = accounts.create(null, new UserAccountService.NewUser(email, "Gestore di prova", role, password));
        return new TestUser(account, password);
    }

    protected static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    protected long audit(String action) {
        return jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = ?").param(action).query(Long.class).single();
    }
}
