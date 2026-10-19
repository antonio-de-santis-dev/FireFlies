package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.fireflies.support.ApiClient;
import it.fireflies.support.ApiClient.Response;
import it.fireflies.support.SecurityIntegrationTest;

/** Login, sessione, logout, CSRF, sessioni scadute e tentativi ripetuti, via HTTP reale (TEST_STRATEGY §7). */
class AuthenticationIT extends SecurityIntegrationTest {

    @Test
    void credenzialiCorretteAprononoUnaSessioneConIlRuoloDelDatabase() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = browser();

        Response login = browser.login(owner.email(), owner.password());

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().path("user").path("role").asString()).isEqualTo("OWNER");
        assertThat(login.body().path("user").has("password")).isFalse();
        String sessionCookie = login.setCookies().stream().filter(c -> c.startsWith("FF_SESSION=")).findFirst().orElseThrow();
        assertThat(sessionCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax").contains("Path=/");

        Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("email").asString()).isEqualTo("titolare@example.invalid");
        Response session = browser.get("/api/v1/auth/session");
        assertThat(session.body().path("authenticated").asBoolean()).isTrue();
        assertThat(audit("LOGIN_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void emailMaiuscoleESpaziNonCambianoAccount() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        assertThat(browser().login("  STAFF@Example.INVALID ", staff.password()).status()).isEqualTo(200);
    }

    @Test
    void passwordSbagliataEEmailInesistenteDannoLaStessaRisposta() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);

        Response wrongPassword = browser().login(owner.email(), "una-password-sbagliata");
        Response unknownEmail = browser().login("nessuno@example.invalid", "una-password-sbagliata");

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.body().path("detail")).isEqualTo(wrongPassword.body().path("detail"));
        assertThat(audit("LOGIN_FAILED")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE metadata::text LIKE '%sbagliata%' OR object_id LIKE '%nessuno%'")
                .query(Long.class).single()).as("né password né email tentate nell'audit").isZero();
    }

    @Test
    void accountDisattivatoNonEntraEStessaRisposta() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        jdbc.sql("UPDATE app_users SET active = false WHERE id = ?").param(staff.account().id()).update();
        Response login = browser().login(staff.email(), staff.password());
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginSenzaTokenCsrfOConTokenFalsoRifiutato() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        Map<String, String> body = Map.of("email", owner.email(), "password", owner.password());

        Response missing = browser().postWithoutCsrf("/api/v1/auth/login", body);
        ApiClient withCookie = browser();
        withCookie.get("/api/v1/auth/csrf");
        Response forged = withCookie.postWithCsrf("/api/v1/auth/login", body, "token-inventato");

        assertThat(missing.status()).isEqualTo(403);
        assertThat(missing.code()).isEqualTo("CSRF_INVALID");
        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.code()).isEqualTo("CSRF_INVALID");
        assertThat(audit("LOGIN_SUCCEEDED")).isZero();
    }

    @Test
    void scrittureAutenticateRichiedonoComunqueIlCsrf() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = browser();
        browser.login(owner.email(), owner.password());
        Response withoutCsrf = browser.postWithoutCsrf("/api/v1/admin/users",
                Map.of("email", "nuovo@example.invalid", "displayName", "Nuovo", "role", "STAFF", "password", randomPassword()));
        assertThat(withoutCsrf.status()).isEqualTo(403);
        assertThat(withoutCsrf.code()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void ilTokenCsrfCambiaDopoIlLogin() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = browser();
        browser.get("/api/v1/auth/csrf");
        String before = browser.cookie("XSRF-TOKEN").orElseThrow();
        browser.login(owner.email(), owner.password());
        browser.get("/api/v1/auth/csrf");
        assertThat(browser.cookie("XSRF-TOKEN")).isPresent().get().isNotEqualTo(before);
    }

    @Test
    void unIdDiSessioneImpostoDaUnAttaccanteNonVieneAdottato() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        String chosenByAttacker = "00000000-0000-0000-0000-0000000000aa";
        ApiClient victim = browser();
        victim.setCookie("FF_SESSION", chosenByAttacker);
        assertThat(victim.login(owner.email(), owner.password()).status()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT session_id FROM spring_session").query(String.class).list())
                .hasSize(1).doesNotContain(chosenByAttacker);
        ApiClient attacker = browser();
        attacker.setCookie("FF_SESSION", chosenByAttacker);
        assertThat(attacker.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    void logoutChiudeLaSessioneEIlCookieRubatoNonServePiu() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = browser();
        browser.login(owner.email(), owner.password());
        String stolen = browser.cookie("FF_SESSION").orElseThrow();

        Response logout = browser.post("/api/v1/auth/logout", Map.of());
        assertThat(logout.status()).isEqualTo(204);
        assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(401);

        ApiClient attacker = browser();
        attacker.setCookie("FF_SESSION", stolen);
        Response replay = attacker.get("/api/v1/auth/me");
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("SESSION_EXPIRED");
        assertThat(audit("LOGOUT")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session").query(Long.class).single()).isZero();
    }

    @Test
    void sessioneScadutaPerInattivita() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient browser = browser();
        browser.login(staff.email(), staff.password());
        // 9 ore senza attività (limite: 8 ore)
        jdbc.sql("""
                UPDATE spring_session SET last_access_time = last_access_time - 32400000,
                                          expiry_time = expiry_time - 32400000
                """).update();

        Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("SESSION_EXPIRED");
        Response session = browser.get("/api/v1/auth/session");
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.body().path("authenticated").asBoolean()).isFalse();
        assertThat(session.body().path("reason").asString()).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void timeoutDellaSessioneDiOttoOre() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        browser().login(staff.email(), staff.password());
        Integer seconds = jdbc.sql("SELECT max_inactive_interval FROM spring_session").query(Integer.class).single();
        assertThat(seconds).isEqualTo(8 * 3600);
        String principal = jdbc.sql("SELECT principal_name FROM spring_session").query(String.class).single();
        assertThat(principal).as("indice per chiudere le sessioni di un account").isEqualTo(staff.account().id().toString());
    }

    @Test
    void accessoAnonimoAgliEndpointRiservati() {
        ApiClient anonymous = browser();
        for (String path : new String[] {"/api/v1/auth/me", "/api/v1/admin/users", "/api/v1/admin/menu", "/api/v1/qualunque"}) {
            Response response = anonymous.get(path);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.code()).as(path).isEqualTo("UNAUTHENTICATED");
        }
        Response session = anonymous.get("/api/v1/auth/session");
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.body().path("authenticated").asBoolean()).isFalse();
        assertThat(session.body().path("reason").isNull()).isTrue();
    }

    @Test
    void tentativiRipetutiBloccanoLAccountAncheConLaPasswordGiusta() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = browser();
        for (int i = 0; i < 5; i++) {
            assertThat(browser.login(owner.email(), "sbagliata-" + i).status()).isEqualTo(401);
        }
        Response blocked = browser.login(owner.email(), owner.password());

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.code()).isEqualTo("RATE_LIMITED");
        assertThat(blocked.header("Retry-After")).isPresent();
        assertThat(Long.parseLong(blocked.header("Retry-After").orElseThrow())).isBetween(1L, 900L);
        AppUser locked = accounts.list().getFirst();
        assertThat(locked.lockedUntil()).isNotNull();
        assertThat(audit("ACCOUNT_LOCKED")).isEqualTo(1);

        // Anche dopo un riavvio (contatori in memoria azzerati) il blocco resta, perché è sul database.
        loginLimiter.clear();
        assertThat(browser.login(owner.email(), owner.password()).status()).isEqualTo(429);

        // Scaduto il blocco si entra e i contatori ripartono da zero.
        jdbc.sql("UPDATE app_users SET locked_until = ? WHERE id = ?")
                .params(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), owner.account().id()).update();
        loginLimiter.clear();
        assertThat(browser.login(owner.email(), owner.password()).status()).isEqualTo(200);
        assertThat(accounts.list().getFirst().failedLoginCount()).isZero();
    }

    @Test
    void ancheUnEmailInesistenteVieneLimitata() {
        ApiClient browser = browser();
        for (int i = 0; i < 5; i++) {
            browser.login("nessuno@example.invalid", "sbagliata-" + i);
        }
        assertThat(browser.login("nessuno@example.invalid", "sbagliata").status())
                .as("stessa risposta che per un account esistente: nessuna enumerazione").isEqualTo(429);
    }

    @Test
    void limitePerIndirizzoIp() {
        ApiClient browser = browser();
        for (int i = 0; i < 20; i++) {
            browser.login("utente" + i + "@example.invalid", "sbagliata");
        }
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        assertThat(browser.login(owner.email(), owner.password()).status()).isEqualTo(429);
    }

    @Test
    void ilRuoloNonSiSceglieAlLogin() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        Response withRole = browser().post("/api/v1/auth/login",
                Map.of("email", staff.email(), "password", staff.password(), "role", "OWNER"));
        assertThat(withRole.status()).isEqualTo(400);
        assertThat(withRole.code()).isEqualTo("MALFORMED_REQUEST");

        ApiClient browser = browser();
        browser.login(staff.email(), staff.password());
        assertThat(browser.get("/api/v1/auth/me").body().path("role").asString()).isEqualTo("STAFF");
    }

    @Test
    void passwordSalvataConBcryptCosto12() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        String hash = jdbc.sql("SELECT password_hash FROM app_users WHERE id = ?").param(owner.account().id())
                .query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(owner.password());
    }

    @Test
    void intestazioniDiSicurezza() {
        Response response = browser().get("/api/v1/auth/session");
        assertThat(response.header("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.header("X-Frame-Options")).contains("DENY");
        assertThat(response.header("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v).contains("frame-ancestors 'none'"));
        assertThat(response.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }
}
