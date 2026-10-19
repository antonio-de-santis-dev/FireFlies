package it.fireflies.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import it.fireflies.security.Role;
import it.fireflies.support.ApiClient;
import it.fireflies.support.SecurityIntegrationTest;
import it.fireflies.support.TestImages;
import tools.jackson.databind.JsonNode;

/** Libreria foto via HTTP reale: permessi, verifica dei file, sostituzione, cancellazione e guasti dello storage. */
class MediaApiIT extends SecurityIntegrationTest {

    @Autowired
    MediaOrphans orphans;

    private ApiClient staff() {
        TestUser user = user("staff." + UUID.randomUUID() + "@example.invalid", Role.STAFF);
        ApiClient client = browser();
        assertThat(client.login(user.email(), user.password()).status()).isEqualTo(200);
        return client;
    }

    private static ApiClient.Response upload(ApiClient client, byte[] image, String alt) {
        return client.upload("POST", "/api/v1/admin/media", "foto.jpg", "image/jpeg", image,
                alt == null ? Map.of() : Map.of("altText", alt));
    }

    private long assets() {
        return jdbc.sql("SELECT count(*) FROM media_assets").query(Long.class).single();
    }

    @Test
    void senzaAccessoNienteLibreriaMaGalleriaPubblica() {
        ApiClient anonymous = browser();
        assertThat(anonymous.get("/api/v1/admin/media").status()).isEqualTo(401);
        assertThat(upload(anonymous, TestImages.jpeg(800, 600), "Bancone").status()).isEqualTo(401);
        ApiClient.Response gallery = anonymous.get("/api/v1/public/gallery");
        assertThat(gallery.status()).isEqualTo(200);
        assertThat(gallery.header("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("public"));
    }

    @Test
    void uploadSenzaTokenCsrfRifiutatoAncheConSessioneValida() {
        ApiClient client = staff();
        ApiClient.Response forged = client.postWithoutCsrf("/api/v1/admin/media", Map.of());
        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.code()).isEqualTo("CSRF_INVALID");
        assertThat(client.delete("/api/v1/admin/media/" + UUID.randomUUID()).status()).as("con token: 404").isEqualTo(404);
    }

    @Test
    void staffCaricaUnaFotoEIlBackendServeLeVarianti() {
        ApiClient client = staff();
        ApiClient.Response created = upload(client, TestImages.jpegWithExif(2000, 1500, 1), "Tagliere sul bancone");
        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.body();
        assertThat(body.path("alt").asString()).isEqualTo("Tagliere sul bancone");
        assertThat(body.path("width").asInt()).isEqualTo(2000);
        assertThat(body.path("variants")).hasSize(3);
        assertThat(body.path("srcset").asString()).contains(" 480w").contains(" 960w").contains(" 1600w");

        String src = body.path("src").asString();
        assertThat(src).startsWith("/media/").endsWith("/w960.jpg");
        HttpResponse<byte[]> file = client.download(src);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(file.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("immutable"));
        assertThat(TestImages.contains(file.body(), TestImages.GPS_MARKER)).as("GPS rimosso").isFalse();
        assertThat(audit("MEDIA_UPLOADED")).isEqualTo(1);

        // un anonimo vede la variante (è pubblica) ma non la libreria
        assertThat(browser().download(src).statusCode()).isEqualTo(200);
    }

    @Test
    void fileNonValidiRifiutatiSenzaToccareStorageEDatabase() {
        ApiClient client = staff();
        ApiClient.Response fake = client.upload("POST", "/api/v1/admin/media", "virus.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes(), Map.of("altText", "Non è una foto"));
        assertThat(fake.status()).isEqualTo(422);
        assertThat(fake.code()).isEqualTo("MEDIA_INVALID");

        ApiClient.Response noAlt = upload(client, TestImages.jpeg(800, 600), null);
        assertThat(noAlt.status()).isEqualTo(422);
        assertThat(noAlt.body().path("context").path("field").asString()).isEqualTo("altText");

        ApiClient.Response bomb = client.upload("POST", "/api/v1/admin/media", "bomba.png", "image/png",
                TestImages.pngHeader(40_000, 40_000), Map.of("altText", "Immagine enorme"));
        assertThat(bomb.status()).isEqualTo(422);
        assertThat(assets()).isZero();
    }

    @Test
    void oltreCinqueMegabyteRisposta413() {
        ApiClient client = staff();
        byte[] big = new byte[6 * 1024 * 1024];
        new java.util.Random(1).nextBytes(big);
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        ApiClient.Response response = upload(client, big, "Foto troppo pesante");
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("MEDIA_TOO_LARGE");
        assertThat(assets()).isZero();
    }

    @Test
    void sostituzioneMantieneIdEUsiECancellaIFileVecchi() {
        ApiClient client = staff();
        JsonNode first = upload(client, TestImages.jpeg(1200, 800), "Cocktail rosso").body();
        String id = first.path("id").asString();
        String oldSrc = first.path("src").asString();

        ApiClient.Response replaced = client.upload("PUT", "/api/v1/admin/media/" + id + "/file", "nuova.png", "image/png",
                TestImages.png(900, 1200, false), Map.of());
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(replaced.body().path("id").asString()).isEqualTo(id);
        assertThat(replaced.body().path("alt").asString()).isEqualTo("Cocktail rosso");
        assertThat(replaced.body().path("height").asInt()).isEqualTo(1200);
        String newSrc = replaced.body().path("src").asString();
        assertThat(newSrc).isNotEqualTo(oldSrc);
        assertThat(client.download(newSrc).statusCode()).isEqualTo(200);
        assertThat(client.download(oldSrc).statusCode()).as("vecchio file cancellato").isEqualTo(404);
        assertThat(audit("MEDIA_REPLACED")).isEqualTo(1);
    }

    @Test
    void modificaTestoGalleriaEOrdine() {
        ApiClient client = staff();
        String a = upload(client, TestImages.jpeg(800, 600), "Prima foto").body().path("id").asString();
        String b = upload(client, TestImages.jpeg(800, 600), "Seconda foto").body().path("id").asString();

        ApiClient.Response patched = client.patch("/api/v1/admin/media/" + a, Map.of("altText", "Bancone illuminato di blu", "inGallery", true));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().path("inGallery").asBoolean()).isTrue();
        assertThat(client.patch("/api/v1/admin/media/" + a, Map.of("altText", "no")).status()).isEqualTo(422);

        client.patch("/api/v1/admin/media/" + b, Map.of("inGallery", true));
        assertThat(client.put("/api/v1/admin/media/order", Map.of("mediaIds", List.of(b, a))).status()).isEqualTo(200);
        JsonNode gallery = browser().get("/api/v1/public/gallery").body();
        assertThat(gallery).hasSize(2);
        assertThat(gallery.get(0).path("id").asString()).isEqualTo(b);
        assertThat(gallery.get(1).path("alt").asString()).isEqualTo("Bancone illuminato di blu");
    }

    @Test
    void fotoInUsoNonSiEliminaQuellaLiberaSi() {
        ApiClient client = staff();
        String used = upload(client, TestImages.jpeg(800, 600), "Tagliere del menu").body().path("id").asString();
        String free = upload(client, TestImages.jpeg(800, 600), "Foto non usata").body().path("id").asString();
        String category = client.post("/api/v1/admin/menu/categories", Map.of("name", "Aperitivi", "visible", true))
                .body().path("id").asString();
        assertThat(client.post("/api/v1/admin/menu/items", Map.of("categoryId", category, "name", "Tagliere",
                "allergens", Map.of("declared", false, "codes", List.of()), "photoMediaId", used, "visible", true)).status())
                .isEqualTo(201);

        ApiClient.Response blocked = client.delete("/api/v1/admin/media/" + used);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("MEDIA_IN_USE");
        assertThat(blocked.body().path("detail").asString()).contains("menu «Tagliere»");
        assertThat(blocked.body().path("context").path("usages").get(0).path("type").asString()).isEqualTo("MENU_ITEM");

        // ultima difesa: anche aggirando l'API il database rifiuta
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM media_assets WHERE id = ?::uuid").param(used).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        String freeSrc = client.get("/api/v1/admin/media").body().findValues("src").stream().map(JsonNode::asString)
                .filter(s -> s.contains(free)).findFirst().orElseThrow();
        assertThat(client.delete("/api/v1/admin/media/" + free).status()).isEqualTo(204);
        assertThat(client.download(freeSrc).statusCode()).isEqualTo(404);
        assertThat(client.delete("/api/v1/admin/media/" + free).status()).isEqualTo(404);
        assertThat(audit("MEDIA_DELETED")).isEqualTo(1);
    }

    @Test
    void storageIrraggiungibileAlCaricamento503ENienteScritto() {
        ApiClient client = staff();
        mediaStorage.failPuts(true);
        ApiClient.Response response = upload(client, TestImages.jpeg(800, 600), "Foto durante il guasto");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.header("Retry-After")).isPresent();
        assertThat(assets()).isZero();

        mediaStorage.failPuts(false);
        assertThat(upload(client, TestImages.jpeg(800, 600), "Foto dopo il guasto").status()).isEqualTo(201);
    }

    @Test
    void storageIrraggiungibileInSostituzioneLasciaLaFotoVecchia() {
        ApiClient client = staff();
        JsonNode photo = upload(client, TestImages.jpeg(800, 600), "Foto da sostituire").body();
        mediaStorage.failPuts(true);
        ApiClient.Response response = client.upload("PUT", "/api/v1/admin/media/" + photo.path("id").asString() + "/file",
                "nuova.jpg", "image/jpeg", TestImages.jpeg(1000, 700), Map.of());
        assertThat(response.status()).isEqualTo(503);
        mediaStorage.failPuts(false);
        assertThat(client.download(photo.path("src").asString()).statusCode()).isEqualTo(200);
        assertThat(client.get("/api/v1/admin/media").body().get(0).path("width").asInt()).isEqualTo(800);
    }

    @Test
    void cancellazioneConStorageGuastoRiprovataDalJob() {
        ApiClient client = staff();
        JsonNode photo = upload(client, TestImages.jpeg(800, 600), "Foto da eliminare").body();
        mediaStorage.failDeletes(true);
        assertThat(client.delete("/api/v1/admin/media/" + photo.path("id").asString()).status())
                .as("per il sito la foto è eliminata subito").isEqualTo(204);
        long pending = jdbc.sql("SELECT count(*) FROM media_orphans").query(Long.class).single();
        assertThat(pending).isEqualTo(2); // W480 e W960 (la foto è larga 800 px)
        assertThat(client.download(photo.path("src").asString()).statusCode()).as("file ancora nello storage").isEqualTo(200);

        jdbc.sql("UPDATE media_orphans SET next_try_at = now() - interval '1 minute'").update();
        assertThat(orphans.sweep()).as("storage ancora guasto").isZero();
        assertThat(jdbc.sql("SELECT min(attempts) FROM media_orphans").query(Integer.class).single()).isEqualTo(1);

        mediaStorage.failDeletes(false);
        jdbc.sql("UPDATE media_orphans SET next_try_at = now() - interval '1 minute'").update();
        assertThat(orphans.sweep()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM media_orphans").query(Long.class).single()).isZero();
        assertThat(client.download(photo.path("src").asString()).statusCode()).isEqualTo(404);
    }
}
