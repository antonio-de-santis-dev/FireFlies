package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.fireflies.support.ApiClient;
import it.fireflies.support.ApiClient.Response;
import it.fireflies.support.SecurityIntegrationTest;

/** Ruoli OWNER/STAFF, gestione degli account e impossibilità di assegnarsi un ruolo. */
class UserAdministrationIT extends SecurityIntegrationTest {

    private ApiClient signedIn(TestUser user) {
        ApiClient browser = browser();
        assertThat(browser.login(user.email(), user.password()).status()).isEqualTo(200);
        return browser;
    }

    @Test
    void staffNonGestisceGliAccountETitolareSi() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        TestUser staff = user("staff@example.invalid", Role.STAFF);

        Response staffList = signedIn(staff).get("/api/v1/admin/users");
        Response ownerList = signedIn(owner).get("/api/v1/admin/users");

        assertThat(staffList.status()).isEqualTo(403);
        assertThat(staffList.code()).isEqualTo("FORBIDDEN");
        assertThat(audit("ACCESS_DENIED")).isEqualTo(1);
        assertThat(ownerList.status()).isEqualTo(200);
        assertThat(ownerList.body()).hasSize(2);
        assertThat(ownerList.raw()).doesNotContain("password").doesNotContain("$2a$");
    }

    @Test
    void staffAutenticatoRaggiungeLAreaGestoriMaNonQuellaDelTitolare() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient browser = signedIn(staff);
        // Dalla FASE 4 il menu esiste: lo staff lo gestisce, ma account, testi e impostazioni del sito restano del titolare.
        assertThat(browser.get("/api/v1/admin/menu").status()).isEqualTo(200);
        assertThat(browser.get("/api/v1/admin/media").status()).isEqualTo(200);
        assertThat(browser.get("/api/v1/admin/users").status()).isEqualTo(403);
        assertThat(browser.get("/api/v1/admin/site-content").status()).isEqualTo(403);
        assertThat(browser.get("/api/v1/admin/site-settings").status()).isEqualTo(403);
    }

    @Test
    void staffNonPuoCambiarsiRuoloNeCrearsiAccount() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient browser = signedIn(staff);

        Response promote = browser.patch("/api/v1/admin/users/" + staff.account().id(),
                Map.of("version", staff.account().version(), "role", "OWNER"));
        Response create = browser.post("/api/v1/admin/users",
                Map.of("email", "complice@example.invalid", "displayName", "Complice", "role", "OWNER", "password", randomPassword()));

        assertThat(promote.status()).isEqualTo(403);
        assertThat(create.status()).isEqualTo(403);
        assertThat(accounts.list()).hasSize(1).allMatch(u -> u.role() == Role.STAFF);
    }

    @Test
    void titolareCreaUnoStaffCheEntraConIlSuoRuolo() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        String password = randomPassword();
        Response created = signedIn(owner).post("/api/v1/admin/users",
                Map.of("email", "Nuovo.Staff@Example.invalid", "displayName", "Nuovo staff", "role", "STAFF", "password", password));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("email").asString()).isEqualTo("nuovo.staff@example.invalid");
        assertThat(created.body().path("role").asString()).isEqualTo("STAFF");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE action = 'USER_CREATED' AND actor_user_id = ?")
                .param(owner.account().id()).query(Long.class).single()).isEqualTo(1);
        Response login = browser().login("nuovo.staff@example.invalid", password);
        assertThat(login.body().path("user").path("role").asString()).isEqualTo("STAFF");
    }

    @Test
    void passwordDeboleEdEmailDoppiaRifiutate() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = signedIn(owner);

        Response weak = browser.post("/api/v1/admin/users",
                Map.of("email", "staff@example.invalid", "displayName", "Staff", "role", "STAFF", "password", "corta"));
        Response duplicate = browser.post("/api/v1/admin/users",
                Map.of("email", "TITOLARE@example.invalid", "displayName", "Doppio", "role", "STAFF", "password", randomPassword()));
        Response invalidRole = browser.post("/api/v1/admin/users",
                Map.of("email", "admin@example.invalid", "displayName", "Admin", "role", "ADMIN", "password", randomPassword()));

        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.code()).isEqualTo("WEAK_PASSWORD");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("EMAIL_TAKEN");
        assertThat(invalidRole.status()).isEqualTo(400);
    }

    @Test
    void cambioDiRuoloChiudeLeSessioniDellAccount() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient staffBrowser = signedIn(staff);

        Response promoted = signedIn(owner).patch("/api/v1/admin/users/" + staff.account().id(),
                Map.of("version", staff.account().version(), "role", "OWNER"));

        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.body().path("role").asString()).isEqualTo("OWNER");
        Response old = staffBrowser.get("/api/v1/auth/me");
        assertThat(old.status()).as("la sessione con il vecchio ruolo non vale più").isEqualTo(401);
        assertThat(old.code()).isEqualTo("SESSION_EXPIRED");
        assertThat(signedIn(staff).get("/api/v1/admin/users").status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT metadata->>'role' FROM audit_logs WHERE action = 'USER_UPDATED'").query(String.class).single())
                .isEqualTo("STAFF→OWNER");
    }

    @Test
    void disattivazioneChiudeLeSessioniEImpedisceIlLogin() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient staffBrowser = signedIn(staff);

        signedIn(owner).patch("/api/v1/admin/users/" + staff.account().id(),
                Map.of("version", staff.account().version(), "active", false));

        assertThat(staffBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(browser().login(staff.email(), staff.password()).status()).isEqualTo(401);
    }

    @Test
    void lUltimoTitolareNonPuoEssereTolto() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        ApiClient browser = signedIn(owner);

        Response demote = browser.patch("/api/v1/admin/users/" + owner.account().id(),
                Map.of("version", owner.account().version(), "role", "STAFF"));
        Response deactivate = browser.patch("/api/v1/admin/users/" + owner.account().id(),
                Map.of("version", owner.account().version(), "active", false));

        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.code()).isEqualTo("LAST_OWNER");
        assertThat(deactivate.code()).isEqualTo("LAST_OWNER");
        assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void versioneVecchiaRifiutata() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient browser = signedIn(owner);
        browser.patch("/api/v1/admin/users/" + staff.account().id(), Map.of("version", 0, "displayName", "Primo"));
        Response stale = browser.patch("/api/v1/admin/users/" + staff.account().id(), Map.of("version", 0, "displayName", "Secondo"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void resetDellaPasswordDaParteDelTitolare() {
        TestUser owner = user("titolare@example.invalid", Role.OWNER);
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient staffBrowser = signedIn(staff);
        String newPassword = randomPassword();

        Response reset = signedIn(owner).post("/api/v1/admin/users/" + staff.account().id() + "/password",
                Map.of("newPassword", newPassword));

        assertThat(reset.status()).isEqualTo(204);
        assertThat(staffBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(browser().login(staff.email(), staff.password()).status()).isEqualTo(401);
        assertThat(browser().login(staff.email(), newPassword).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE metadata::text LIKE ?").param("%" + newPassword + "%")
                .query(Long.class).single()).isZero();
    }

    @Test
    void cambioDellaPropriaPassword() {
        TestUser staff = user("staff@example.invalid", Role.STAFF);
        ApiClient here = signedIn(staff);
        ApiClient otherDevice = signedIn(staff);
        String newPassword = randomPassword();

        Map<String, String> wrongCurrent = new HashMap<>(Map.of("currentPassword", "non-e-questa", "newPassword", newPassword));
        assertThat(here.post("/api/v1/auth/password", wrongCurrent).code()).isEqualTo("CURRENT_PASSWORD_WRONG");
        assertThat(here.post("/api/v1/auth/password", Map.of("currentPassword", staff.password(), "newPassword", "staff12345678"))
                .code()).isEqualTo("WEAK_PASSWORD");

        Response changed = here.post("/api/v1/auth/password", Map.of("currentPassword", staff.password(), "newPassword", newPassword));

        assertThat(changed.status()).isEqualTo(204);
        assertThat(here.get("/api/v1/auth/me").status()).as("la sessione corrente resta").isEqualTo(200);
        assertThat(otherDevice.get("/api/v1/auth/me").status()).as("le altre sessioni si chiudono").isEqualTo(401);
        assertThat(browser().login(staff.email(), newPassword).status()).isEqualTo(200);
        assertThat(audit("PASSWORD_CHANGED")).isEqualTo(1);
    }
}
