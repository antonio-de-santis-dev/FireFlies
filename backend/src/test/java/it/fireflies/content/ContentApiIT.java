package it.fireflies.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.fireflies.common.BusinessTime;
import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.SecurityIntegrationTest;
import it.fireflies.support.TestImages;
import tools.jackson.databind.JsonNode;

/** Contenuti del sito (solo titolare), impostazioni e orari (solo titolare), avvisi (staff), lettura pubblica. */
class ContentApiIT extends SecurityIntegrationTest {

    private ApiClient signedIn(Role role) {
        TestUser user = user(role.name().toLowerCase() + "." + UUID.randomUUID() + "@example.invalid", role);
        ApiClient client = browser();
        assertThat(client.login(user.email(), user.password()).status()).isEqualTo(200);
        return client;
    }

    private static String photo(ApiClient client, String alt, boolean transparent) {
        ApiClient.Response r = client.upload("POST", "/api/v1/admin/media", "f.png", "image/png",
                TestImages.png(600, 900, transparent), Map.of("altText", alt));
        assertThat(r.status()).isEqualTo(201);
        return r.body().path("id").asString();
    }

    private static Map<String, Object> content(int version, String front, String back, String staffPhoto, List<String> hero) {
        Map<String, Object> owner = new HashMap<>();
        owner.put("name", "Nome titolare");
        owner.put("roleLabel", "Titolare");
        owner.put("description", "Due righe su di lui.");
        owner.put("motto", "Il mio gruppo sanguigno è A-PERITIVO.");
        owner.put("color", "giallo");
        owner.put("frontMediaId", front);
        owner.put("backMediaId", back);
        owner.put("visible", true);
        Map<String, Object> hiddenOwner = new HashMap<>(owner);
        hiddenOwner.put("name", "Titolare nascosto");
        hiddenOwner.put("visible", false);
        Map<String, Object> staff = new HashMap<>();
        staff.put("name", "Bartender");
        staff.put("roleLabel", "Al bancone");
        staff.put("mediaId", staffPhoto);
        staff.put("visible", true);
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("heroClaim", "Dal primo caffè all'ultimo brindisi.");
        body.put("heroLead", "Colazioni, aperitivi e serate a Martano.");
        body.put("heroMediaId", staffPhoto);
        body.put("sections", Map.of("eventi", false));
        body.put("storyTitle", "Perché FireFlies");
        body.put("storyText", "Primo paragrafo.\n\nSecondo paragrafo.");
        body.put("storyConfirmed", true);
        body.put("owners", new ArrayList<>(List.of(owner, hiddenOwner)));
        body.put("ownersConfirmed", false);
        body.put("staff", new ArrayList<>(List.of(staff)));
        body.put("staffConfirmed", true);
        body.put("menuHeroMediaIds", hero);
        body.put("menuHeroIntervalSec", 30);
        return body;
    }

    private static Map<String, Object> settings(int version) {
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if (day == 3) {
                hours.add(Map.of("isoDay", day, "closed", true));
            } else if (day == 6) {
                hours.add(Map.of("isoDay", day, "closed", false, "opensAt", "18:00", "closesAt", "02:00"));
            } else {
                hours.add(Map.of("isoDay", day, "closed", false, "opensAt", "07:00", "closesAt", "23:00"));
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("addressLine", "Via Giovanni XXIII, 19 — 73025 Martano (LE)");
        body.put("addressConfirmed", true);
        body.put("mapQuery", "FireFlies Aperitif, Via Giovanni XXIII, 19, 73025 Martano LE");
        body.put("mapMode", "CONSENT");
        body.put("instagramUrl", "https://www.instagram.com/fireflies.aperitif/");
        body.put("hoursConfirmed", true);
        body.put("hours", hours);
        return body;
    }

    @Test
    void contenutiEImpostazioniSoloAlTitolare() {
        ApiClient staff = signedIn(Role.STAFF);
        assertThat(staff.get("/api/v1/admin/site-content").status()).isEqualTo(403);
        assertThat(staff.put("/api/v1/admin/site-content", Map.of("version", 0)).status()).isEqualTo(403);
        assertThat(staff.get("/api/v1/admin/site-settings").status()).isEqualTo(403);
        ApiClient.Response put = staff.put("/api/v1/admin/site-settings", settings(0));
        assertThat(put.status()).isEqualTo(403);
        assertThat(put.code()).isEqualTo("FORBIDDEN");
        assertThat(browser().get("/api/v1/admin/site-content").status()).isEqualTo(401);
        assertThat(signedIn(Role.OWNER).get("/api/v1/admin/site-content").status()).isEqualTo(200);
    }

    @Test
    void ilTitolarePubblicaTestiTitolariStaffETestata() {
        ApiClient owner = signedIn(Role.OWNER);
        String front = photo(owner, "Titolare con maglia gialla", true);
        String back = photo(owner, "Retro della maglia gialla", true);
        String bar = photo(owner, "Bartender al bancone", false);
        String drink = photo(owner, "Calice con lamponi", false);
        int version = owner.get("/api/v1/admin/site-content").body().path("version").asInt();

        ApiClient.Response saved = owner.put("/api/v1/admin/site-content", content(version, front, back, bar, List.of(drink, bar)));
        assertThat(saved.status()).isEqualTo(200);
        JsonNode body = saved.body();
        assertThat(body.path("version").asInt()).isEqualTo(version + 1);
        assertThat(body.path("owners")).hasSize(2);
        assertThat(body.path("owners").get(0).path("front").path("src").asString()).endsWith(".png");
        assertThat(body.path("menuHero").path("photos").get(0).path("id").asString()).isEqualTo(drink);
        assertThat(body.path("menuHero").path("intervalSec").asInt()).isEqualTo(30);
        assertThat(body.path("sections").path("eventi").asBoolean()).isFalse();
        assertThat(body.path("sections").path("momento").asBoolean()).isTrue();

        JsonNode site = browser().get("/api/v1/public/site").body();
        assertThat(site.path("content").path("heroClaim").asString()).isEqualTo("Dal primo caffè all'ultimo brindisi.");
        assertThat(site.path("content").path("owners")).as("solo titolari visibili").hasSize(1);
        assertThat(site.path("content").path("staff").get(0).path("photo").path("alt").asString()).isEqualTo("Bartender al bancone");
        assertThat(site.path("demoContent").asBoolean()).isFalse();

        // la foto è in uso: non si elimina finché non la si toglie dai contenuti
        ApiClient.Response blocked = owner.delete("/api/v1/admin/media/" + front);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().path("detail").asString()).contains("titolare «Nome titolare»");
        assertThat(owner.delete("/api/v1/admin/media/" + drink).body().path("detail").asString()).contains("testata del menu");

        // ripubblicare con una lista più corta toglie le persone mancanti
        Map<String, Object> fewer = content(version + 1, front, back, bar, List.of());
        fewer.put("staff", List.of());
        JsonNode after = owner.put("/api/v1/admin/site-content", fewer).body();
        assertThat(after.path("staff")).isEmpty();
        assertThat(after.path("menuHero").path("photos")).isEmpty();
        assertThat(owner.delete("/api/v1/admin/media/" + drink).status()).isEqualTo(204);
        assertThat(audit("SITE_CONTENT_UPDATED")).isEqualTo(2);
    }

    @Test
    void validazioneEConcorrenzaDeiContenuti() {
        ApiClient owner = signedIn(Role.OWNER);
        String p = photo(owner, "Foto qualunque", false);
        int version = owner.get("/api/v1/admin/site-content").body().path("version").asInt();

        Map<String, Object> shortClaim = content(version, p, p, p, List.of());
        shortClaim.put("heroClaim", "Ok");
        assertThat(owner.put("/api/v1/admin/site-content", shortClaim).body().path("context").path("field").asString()).isEqualTo("heroClaim");

        Map<String, Object> unknownSection = content(version, p, p, p, List.of());
        unknownSection.put("sections", Map.of("casinò", true));
        assertThat(owner.put("/api/v1/admin/site-content", unknownSection).status()).isEqualTo(422);

        Map<String, Object> missingPhoto = content(version, UUID.randomUUID().toString(), p, p, List.of());
        assertThat(owner.put("/api/v1/admin/site-content", missingPhoto).body().path("code").asString()).isEqualTo("MEDIA_NOT_FOUND");

        Map<String, Object> interval = content(version, p, p, p, List.of());
        interval.put("menuHeroIntervalSec", 5);
        assertThat(owner.put("/api/v1/admin/site-content", interval).status()).isEqualTo(422);

        assertThat(owner.put("/api/v1/admin/site-content", content(version, p, p, p, List.of())).status()).isEqualTo(200);
        ApiClient.Response stale = owner.put("/api/v1/admin/site-content", content(version, p, p, p, List.of()));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void orariEContatti() {
        ApiClient owner = signedIn(Role.OWNER);
        int version = owner.get("/api/v1/admin/site-settings").body().path("version").asInt();
        ApiClient.Response saved = owner.put("/api/v1/admin/site-settings", settings(version));
        assertThat(saved.status()).isEqualTo(200);
        JsonNode hours = saved.body().path("hours");
        assertThat(hours).hasSize(7);
        assertThat(hours.get(2).path("closed").asBoolean()).isTrue();
        assertThat(hours.get(5).path("closesAt").asString()).startsWith("02:00");
        assertThat(jdbc.sql("SELECT closes_next_day FROM opening_hours WHERE iso_day = 6").query(Boolean.class).single())
                .as("chiusura dopo mezzanotte").isTrue();

        int next = saved.body().path("version").asInt();
        Map<String, Object> phone = settings(next);
        phone.put("phone", "chiamaci");
        assertThat(owner.put("/api/v1/admin/site-settings", phone).body().path("context").path("field").asString()).isEqualTo("phone");
        Map<String, Object> email = settings(next);
        email.put("email", "info@");
        assertThat(owner.put("/api/v1/admin/site-settings", email).status()).isEqualTo(422);
        Map<String, Object> instagram = settings(next);
        instagram.put("instagramUrl", "https://phishing.example/fireflies");
        assertThat(owner.put("/api/v1/admin/site-settings", instagram).status()).isEqualTo(422);
        Map<String, Object> sixDays = settings(next);
        sixDays.put("hours", ((List<?>) sixDays.get("hours")).subList(0, 6));
        assertThat(owner.put("/api/v1/admin/site-settings", sixDays).status()).isEqualTo(422);
        Map<String, Object> mode = settings(next);
        mode.put("mapMode", "SEMPRE");
        assertThat(owner.put("/api/v1/admin/site-settings", mode).status()).isEqualTo(422);

        JsonNode site = browser().get("/api/v1/public/site").body().path("settings");
        assertThat(site.path("hoursConfirmed").asBoolean()).isTrue();
        assertThat(site.path("addressLine").asString()).contains("Martano");
        assertThat(owner.put("/api/v1/admin/site-settings", settings(version)).code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void avvisiGestitiDalloStaffEScadenze() {
        ApiClient staff = signedIn(Role.STAFF);
        LocalDate today = BusinessTime.serviceDate(java.time.Instant.now());
        ApiClient.Response created = staff.post("/api/v1/admin/announcements",
                Map.of("text", "Stasera chiusura anticipata alle 21:00", "tone", "IMPORTANT", "active", true, "visibleUntil", today.toString()));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("visibleUntil").asString()).isEqualTo(today.toString());
        String id = created.body().path("id").asString();

        assertThat(staff.post("/api/v1/admin/announcements", Map.of("text", "Ieri", "tone", "INFO", "active", true)).status())
                .as("testo troppo corto").isEqualTo(422);
        assertThat(staff.post("/api/v1/admin/announcements", Map.of("text", "Avviso del passato", "tone", "INFO", "active", true,
                "visibleUntil", today.minusDays(1).toString())).status()).isEqualTo(422);
        assertThat(staff.post("/api/v1/admin/announcements", Map.of("text", "Tono sconosciuto", "tone", "URGENTE", "active", true)).status())
                .isEqualTo(422);
        staff.post("/api/v1/admin/announcements", Map.of("text", "Bozza non ancora pubblicata", "tone", "INFO", "active", false));

        // scaduto ieri: scritto direttamente, come se fosse passato il tempo
        jdbc.sql("INSERT INTO announcements (text, tone, active, visible_until) VALUES ('Avviso scaduto', 'INFO', true, now() - interval '1 hour')").update();

        JsonNode live = browser().get("/api/v1/public/site").body().path("announcements");
        assertThat(live).hasSize(1);
        assertThat(live.get(0).path("text").asString()).isEqualTo("Stasera chiusura anticipata alle 21:00");
        assertThat(staff.get("/api/v1/admin/announcements").body()).hasSize(3);

        ApiClient.Response off = staff.put("/api/v1/admin/announcements/" + id,
                Map.of("text", "Stasera chiusura anticipata alle 21:00", "tone", "IMPORTANT", "active", false));
        assertThat(off.body().path("active").asBoolean()).isFalse();
        assertThat(browser().get("/api/v1/public/site").body().path("announcements")).isEmpty();
        assertThat(staff.delete("/api/v1/admin/announcements/" + id).status()).isEqualTo(204);
        assertThat(staff.delete("/api/v1/admin/announcements/" + id).status()).isEqualTo(404);
        assertThat(audit("ANNOUNCEMENT_CREATED")).isEqualTo(2);
    }

    @Test
    void scrittureDelSitoPubblicoNonEsistono() {
        ApiClient anonymous = browser();
        assertThat(anonymous.post("/api/v1/public/site", Map.of()).status()).isIn(401, 403);
        assertThat(anonymous.get("/api/v1/public/site").header("ETag")).isPresent();
    }
}
