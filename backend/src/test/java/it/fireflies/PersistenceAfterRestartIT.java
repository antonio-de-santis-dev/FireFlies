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
        String reference;
        String day = java.time.LocalDate.now(it.fireflies.common.BusinessTime.ZONE).plusDays(7).toString();
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
            // prenotazioni: zona, fascia, regole, una richiesta dal sito confermata
            JsonNode zone = owner.get("/api/v1/admin/booking-config").body().path("zones").get(0);
            String zoneId = zone.path("id").asString();
            assertThat(owner.put("/api/v1/admin/capacity-zones/" + zoneId, Map.of("version", zone.path("version").asInt(),
                    "name", "Locale", "physicalCapacity", 6, "active", true)).status()).isEqualTo(200);
            assertThat(owner.post("/api/v1/admin/service-windows", Map.of("zoneId", zoneId,
                    "isoDay", java.time.LocalDate.parse(day).getDayOfWeek().getValue(), "name", "Aperitivo", "firstStart", "19:00",
                    "lastStart", "19:00", "slotMinutes", 30, "defaultDurationMinutes", 120)).status()).isEqualTo(201);
            assertThat(owner.put("/api/v1/admin/booking-policy", Map.of("webBookingEnabled", true, "minLeadMinutes", 60,
                    "maxAdvanceDays", 60, "maxPartySizeWeb", 12, "pendingExpiryMinutesBeforeStart", 60)).status()).isEqualTo(200);
            ApiClient.Response request = client(first).post("/api/v1/public/reservations", Map.of("date", day, "time", "19:00",
                    "guests", 6, "name", "Cliente di prova", "phone", "300 000 0099", "privacyAccepted", true),
                    Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
            assertThat(request.status()).as(request.raw()).isEqualTo(201);
            reference = request.body().path("reference").asString();
            JsonNode listed = owner.get("/api/v1/admin/reservations").body().path("items").get(0);
            assertThat(owner.patch("/api/v1/admin/reservations/" + listed.path("id").asString() + "/status",
                    Map.of("version", listed.path("version").asInt(), "status", "CONFIRMED"))
                    .status()).isEqualTo(200);
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

            JsonNode reservation = sameBrowser.get("/api/v1/admin/reservations").body().path("items").get(0);
            assertThat(reservation.path("reference").asString()).isEqualTo(reference);
            assertThat(reservation.path("status").asString()).isEqualTo("CONFIRMED");
            JsonNode slots = visitor.get("/api/v1/public/booking-options?date=" + day + "&guests=1").body()
                    .path("groups").get(0).path("slots");
            assertThat(slots.get(0).path("reason").asString()).as("i posti occupati sono nel database, non in memoria").isEqualTo("FULL");
        }
    }
}
