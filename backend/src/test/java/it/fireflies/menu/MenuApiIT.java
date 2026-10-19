package it.fireflies.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.SecurityIntegrationTest;
import tools.jackson.databind.JsonNode;

/** Menu via HTTP reale: permessi, categorie, voci, prezzi, allergeni, concorrenza e lettura pubblica con ETag. */
class MenuApiIT extends SecurityIntegrationTest {

    private ApiClient signedIn(Role role) {
        TestUser user = user(role.name().toLowerCase() + "." + UUID.randomUUID() + "@example.invalid", role);
        ApiClient client = browser();
        assertThat(client.login(user.email(), user.password()).status()).isEqualTo(200);
        return client;
    }

    private static String category(ApiClient client, String name) {
        ApiClient.Response created = client.post("/api/v1/admin/menu/categories", Map.of("name", name, "visible", true));
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("id").asString();
    }

    private static Map<String, Object> item(String categoryId, String name, Object priceCents, boolean declared, List<String> codes) {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("description", "Descrizione di prova");
        body.put("ingredients", List.of("Pane", "Mortadella", "Pistacchio"));
        body.put("priceCents", priceCents);
        body.put("allergens", Map.of("declared", declared, "codes", codes));
        body.put("visible", true);
        return body;
    }

    @Test
    void permessi() {
        ApiClient anonymous = browser();
        assertThat(anonymous.get("/api/v1/admin/menu").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/public/menu").status()).isEqualTo(200);
        assertThat(signedIn(Role.STAFF).get("/api/v1/admin/menu").status()).as("lo staff gestisce il menu").isEqualTo(200);
        assertThat(signedIn(Role.OWNER).get("/api/v1/admin/menu").status()).isEqualTo(200);
        // scrittura anonima: prima ancora del ruolo serve il token CSRF della sessione
        assertThat(anonymous.post("/api/v1/admin/menu/categories", Map.of("name", "Intrusi", "visible", true)).status()).isEqualTo(401);
    }

    @Test
    void categorie() {
        ApiClient staff = signedIn(Role.STAFF);
        ApiClient.Response created = staff.post("/api/v1/admin/menu/categories",
                Map.of("name", "Analcolici & Soft", "hint", "Per chi guida", "visible", true));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("slug").asString()).isEqualTo("analcolici-soft");

        ApiClient.Response duplicate = staff.post("/api/v1/admin/menu/categories", Map.of("name", "Analcolici soft", "visible", true));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("CATEGORY_SLUG_TAKEN");

        ApiClient.Response badSlug = staff.post("/api/v1/admin/menu/categories", Map.of("name", "Birre", "slug", "Birre Artigianali!", "visible", true));
        assertThat(badSlug.status()).isEqualTo(422);

        String id = created.body().path("id").asString();
        String beers = category(staff, "Birre");
        ApiClient.Response renamed = staff.put("/api/v1/admin/menu/categories/" + id,
                Map.of("name", "Analcolici", "hint", "Senza alcol", "visible", false));
        assertThat(renamed.body().path("slug").asString()).as("lo slug resta stabile per i link").isEqualTo("analcolici-soft");
        assertThat(renamed.body().path("visible").asBoolean()).isFalse();

        assertThat(staff.put("/api/v1/admin/menu/categories/order", Map.of("categoryIds", List.of(beers, id))).body()
                .get(0).path("id").asString()).isEqualTo(beers);

        staff.post("/api/v1/admin/menu/items", item(beers, "Birra alla spina", 450, false, List.of()));
        ApiClient.Response notEmpty = staff.delete("/api/v1/admin/menu/categories/" + beers);
        assertThat(notEmpty.status()).isEqualTo(409);
        assertThat(notEmpty.code()).isEqualTo("CATEGORY_NOT_EMPTY");
        assertThat(staff.delete("/api/v1/admin/menu/categories/" + id).status()).isEqualTo(204);
        assertThat(audit("MENU_CATEGORY_DELETED")).isEqualTo(1);
    }

    @Test
    void creaModificaNascondiEliminaUnaVoce() {
        ApiClient staff = signedIn(Role.STAFF);
        String cat = category(staff, "Aperitivi");
        ApiClient.Response created = staff.post("/api/v1/admin/menu/items", item(cat, "Crostino mortadella e pistacchio", 350, false, List.of()));
        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.body();
        String id = body.path("id").asString();
        assertThat(body.path("priceCents").asInt()).isEqualTo(350);
        assertThat(body.path("demoPriceCents").isNull()).as("mai un prezzo demo dalle API dei gestori").isTrue();
        assertThat(body.path("ingredients")).hasSize(3);
        assertThat(body.path("categorySlug").asString()).isEqualTo("aperitivi");

        JsonNode publicItem = browser().get("/api/v1/public/menu").body().path("categories").get(0).path("items").get(0);
        assertThat(publicItem.path("name").asString()).isEqualTo("Crostino mortadella e pistacchio");
        assertThat(publicItem.path("priceCents").asInt()).isEqualTo(350);
        assertThat(publicItem.has("version")).as("niente dati interni nel menu pubblico").isFalse();

        Map<String, Object> change = item(cat, "Crostino mortadella", null, false, List.of());
        change.put("version", body.path("version").asInt());
        ApiClient.Response updated = staff.put("/api/v1/admin/menu/items/" + id, change);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("priceCents").isNull()).as("prezzo tolto = «Prezzo da definire»").isTrue();

        ApiClient.Response stale = staff.put("/api/v1/admin/menu/items/" + id, change);
        assertThat(stale.status()).as("versione vecchia").isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");

        ApiClient.Response hidden = staff.patch("/api/v1/admin/menu/items/" + id + "/visibility",
                Map.of("version", updated.body().path("version").asInt(), "visible", false));
        assertThat(hidden.status()).isEqualTo(200);
        assertThat(browser().get("/api/v1/public/menu").body().path("categories").get(0).path("items")).isEmpty();
        assertThat(staff.get("/api/v1/admin/menu").body().path("items")).hasSize(1);

        assertThat(staff.delete("/api/v1/admin/menu/items/" + id).status()).isEqualTo(204);
        assertThat(staff.delete("/api/v1/admin/menu/items/" + id).status()).isEqualTo(404);
        assertThat(audit("MENU_ITEM_CREATED")).isEqualTo(1);
        assertThat(audit("MENU_ITEM_UPDATED")).isEqualTo(2);
        assertThat(audit("MENU_ITEM_DELETED")).isEqualTo(1);
    }

    @Test
    void validazioneDeiPrezzi() {
        ApiClient staff = signedIn(Role.STAFF);
        String cat = category(staff, "Cocktail");
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", -1, false, List.of())).status()).isEqualTo(422);
        ApiClient.Response tooHigh = staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", 100_001, false, List.of()));
        assertThat(tooHigh.status()).isEqualTo(422);
        assertThat(tooHigh.body().path("context").path("field").asString()).isEqualTo("priceCents");
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", 6.5, false, List.of())).status())
                .as("i centesimi sono interi: 6.5 non diventa 6").isEqualTo(400);
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", "sei euro", false, List.of())).status()).isEqualTo(400);
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", 0, false, List.of())).status()).as("gratis è ammesso").isEqualTo(201);
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "Spritz", 100_000, false, List.of())).status()).isEqualTo(201);
        assertThat(staff.post("/api/v1/admin/menu/items", item(cat, "S", 500, false, List.of())).status()).as("nome troppo corto").isEqualTo(422);
        assertThat(staff.post("/api/v1/admin/menu/items", item(UUID.randomUUID().toString(), "Negroni", 800, false, List.of())).status())
                .as("categoria inesistente").isEqualTo(422);
        Map<String, Object> comma = item(cat, "Negroni", 800, false, List.of());
        comma.put("ingredients", List.of("Gin, bitter"));
        assertThat(staff.post("/api/v1/admin/menu/items", comma).status()).isEqualTo(422);
        Map<String, Object> extra = item(cat, "Negroni", 800, false, List.of());
        extra.put("demoPriceCents", 100);
        assertThat(staff.post("/api/v1/admin/menu/items", extra).status()).as("il prezzo demo non si scrive dall'API").isEqualTo(400);
    }

    @Test
    void allergeniDichiaratiSoloDopoLaVerifica() {
        TestUser owner = user("titolare." + UUID.randomUUID() + "@example.invalid", Role.OWNER);
        ApiClient client = browser();
        client.login(owner.email(), owner.password());
        String cat = category(client, "Food");

        ApiClient.Response notVerified = client.post("/api/v1/admin/menu/items", item(cat, "Rustico", 200, false, List.of("GLUTEN")));
        assertThat(notVerified.status()).isEqualTo(422);
        assertThat(notVerified.body().path("detail").asString()).contains("verificati");

        ApiClient.Response unknown = client.post("/api/v1/admin/menu/items", item(cat, "Rustico", 200, true, List.of("GLUTEN", "POMODORO")));
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.body().path("detail").asString()).contains("POMODORO");

        ApiClient.Response declared = client.post("/api/v1/admin/menu/items", item(cat, "Rustico", 200, true, List.of("milk", "GLUTEN")));
        assertThat(declared.status()).isEqualTo(201);
        JsonNode allergens = declared.body().path("allergens");
        assertThat(allergens.path("declared").asBoolean()).isTrue();
        assertThat(allergens.path("codes").toString()).isEqualTo("[\"GLUTEN\",\"MILK\"]"); // ordine del Regolamento
        assertThat(allergens.path("verifiedAt").isNull()).isFalse();
        String id = declared.body().path("id").asString();
        UUID verifier = jdbc.sql("SELECT allergens_verified_by FROM menu_items WHERE id = ?::uuid").param(id).query(UUID.class).single();
        assertThat(verifier).isEqualTo(owner.account().id());

        Map<String, Object> none = item(cat, "Rustico", 200, true, List.of());
        none.put("version", declared.body().path("version").asInt());
        ApiClient.Response noAllergens = client.put("/api/v1/admin/menu/items/" + id, none);
        assertThat(noAllergens.body().path("allergens").path("declared").asBoolean()).as("«nessun allergene» è una dichiarazione").isTrue();
        assertThat(noAllergens.body().path("allergens").path("codes")).isEmpty();

        Map<String, Object> undeclared = item(cat, "Rustico", 200, false, List.of());
        undeclared.put("version", noAllergens.body().path("version").asInt());
        JsonNode back = client.put("/api/v1/admin/menu/items/" + id, undeclared).body().path("allergens");
        assertThat(back.path("declared").asBoolean()).isFalse();
        assertThat(back.path("verifiedAt").isNull()).isTrue();

        JsonNode list = browser().get("/api/v1/public/menu").body().path("allergens");
        assertThat(list).hasSize(14);
        assertThat(list.get(0).path("name").asString()).isEqualTo("Glutine");
    }

    @Test
    void fotoDellaVoceDeveEsistere() {
        ApiClient staff = signedIn(Role.STAFF);
        String cat = category(staff, "Colazione");
        Map<String, Object> body = item(cat, "Cappuccino", 150, false, List.of());
        body.put("photoMediaId", UUID.randomUUID().toString());
        ApiClient.Response response = staff.post("/api/v1/admin/menu/items", body);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("MEDIA_NOT_FOUND");
    }

    @Test
    void menuPubblicoConEtagECache() {
        ApiClient staff = signedIn(Role.STAFF);
        String cat = category(staff, "Birre");
        staff.post("/api/v1/admin/menu/items", item(cat, "Birra", 450, false, List.of()));

        ApiClient visitor = browser();
        ApiClient.Response first = visitor.get("/api/v1/public/menu");
        String etag = first.header("ETag").orElseThrow();
        assertThat(first.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("max-age=60").contains("public"));
        assertThat(visitor.get("/api/v1/public/menu", Map.of("If-None-Match", etag)).status()).isEqualTo(304);

        staff.post("/api/v1/admin/menu/items", item(cat, "Birra rossa", 500, false, List.of()));
        assertThat(visitor.get("/api/v1/public/menu", Map.of("If-None-Match", etag)).status())
                .as("dopo una modifica il menu cambia subito ETag").isEqualTo(200);

        ApiClient.Response admin = staff.get("/api/v1/admin/menu");
        assertThat(admin.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    void categorieNascosteFuoriDalMenuPubblico() {
        ApiClient staff = signedIn(Role.STAFF);
        String hidden = staff.post("/api/v1/admin/menu/categories", Map.of("name", "Fuori menu", "visible", false)).body().path("id").asString();
        staff.post("/api/v1/admin/menu/items", item(hidden, "Piatto segreto", 900, false, List.of()));
        assertThat(browser().get("/api/v1/public/menu").body().path("categories")).isEmpty();
    }
}
