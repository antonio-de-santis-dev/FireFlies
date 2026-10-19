package it.fireflies;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import it.fireflies.security.Role;
import it.fireflies.security.UserAccountService;
import it.fireflies.support.ApiClient;
import it.fireflies.support.PostgresTestcontainer;
import it.fireflies.support.TestImages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Riavvio vero dell'applicazione: due avvii distinti sullo stesso PostgreSQL e sulla stessa cartella delle foto.
 * Menu, foto, contenuti, avvisi e perfino la sessione del gestore sopravvivono (niente stato in memoria).
 */
class PersistenceAfterRestartIT {

    private static PostgreSQLContainer postgres;

    @TempDir
    static Path media;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE).asCompatibleSubstituteFor("postgres"))
                .withEnv("TZ", "UTC");
        postgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static ConfigurableApplicationContext start() {
        // argomenti da riga di comando: hanno la precedenza su application-test.yml (cartella foto casuale)
        return new SpringApplicationBuilder(FirefliesApplication.class).profiles("test").run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--fireflies.media.filesystem.root=" + media);
    }

    private static ApiClient client(ConfigurableApplicationContext app) {
        int port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        return new ApiClient(port, app.getBean(ObjectMapper.class));
    }

    @Test
    void datiEFotoSopravvivonoAlRiavvio() {
        String password = "una frase lunga di prova " + System.nanoTime();
        String photoSrc;
        String sessionCookie;
        try (ConfigurableApplicationContext first = start()) {
            first.getBean(UserAccountService.class).create(null,
                    new UserAccountService.NewUser("titolare.riavvio@example.invalid", "Titolare", Role.OWNER, password));
            ApiClient owner = client(first);
            assertThat(owner.login("titolare.riavvio@example.invalid", password).status()).isEqualTo(200);
            JsonNode photo = owner.upload("POST", "/api/v1/admin/media", "t.jpg", "image/jpeg", TestImages.jpeg(1200, 900),
                    Map.of("altText", "Tagliere dell'aperitivo")).body();
            photoSrc = photo.path("src").asString();
            String category = owner.post("/api/v1/admin/menu/categories", Map.of("name", "Aperitivi", "visible", true))
                    .body().path("id").asString();
            assertThat(owner.post("/api/v1/admin/menu/items", Map.of("categoryId", category, "name", "Tagliere FireFlies",
                    "priceCents", 1400, "allergens", Map.of("declared", true, "codes", List.of("GLUTEN", "MILK")),
                    "photoMediaId", photo.path("id").asString(), "visible", true)).status()).isEqualTo(201);
            assertThat(owner.post("/api/v1/admin/announcements", Map.of("text", "Sabato DJ set dalle 22", "tone", "INFO",
                    "active", true)).status()).isEqualTo(201);
            sessionCookie = owner.cookie("FF_SESSION").orElseThrow();
        }

        try (ConfigurableApplicationContext second = start()) {
            ApiClient visitor = client(second);
            JsonNode item = visitor.get("/api/v1/public/menu").body().path("categories").get(0).path("items").get(0);
            assertThat(item.path("name").asString()).isEqualTo("Tagliere FireFlies");
            assertThat(item.path("priceCents").asInt()).isEqualTo(1400);
            assertThat(item.path("allergens").path("codes").toString()).isEqualTo("[\"GLUTEN\",\"MILK\"]");
            assertThat(item.path("photo").path("src").asString()).isEqualTo(photoSrc);
            assertThat(visitor.download(photoSrc).statusCode()).as("il file della foto è ancora servito").isEqualTo(200);
            assertThat(visitor.get("/api/v1/public/site").body().path("announcements").get(0).path("text").asString())
                    .isEqualTo("Sabato DJ set dalle 22");

            ApiClient sameBrowser = client(second);
            sameBrowser.setCookie("FF_SESSION", sessionCookie);
            assertThat(sameBrowser.get("/api/v1/auth/me").status()).as("la sessione è in PostgreSQL").isEqualTo(200);
        }
    }
}
