package it.fireflies.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.content.ContentService;
import it.fireflies.media.MediaService;
import it.fireflies.menu.MenuService;
import tools.jackson.databind.ObjectMapper;

/**
 * Riempie un database locale VUOTO con i contenuti del mockup approvato, passando dalle stesse API interne dei
 * gestori (stesse validazioni, stessa ricodifica delle foto). Esiste solo con il profilo {@code local} e
 * {@code fireflies.demo.seed=true}; segna il database come «demo», cosa che in produzione blocca l'avvio.
 * Le voci del menu hanno solo prezzi DIMOSTRATIVI ({@code demo_price_cents}) e allergeni «da dichiarare».
 */
@Component
@Profile("local")
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "fireflies.demo.seed", havingValue = "true")
class DemoContentLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContentLoader.class);

    record DemoMedia(String key, String file, String alt, boolean inGallery) {
    }

    record DemoCategory(String slug, String name, String hint) {
    }

    record DemoItem(String category, String name, String description, List<String> ingredients, Integer demoPriceCents,
            String photo, boolean visible) {
    }

    record DemoOwner(String name, String roleLabel, String description, String motto, String color, String front, String back) {
    }

    record DemoStaff(String name, String roleLabel, String photo) {
    }

    record DemoMenuHero(List<String> photos, int intervalSec) {
    }

    record DemoContent(String heroClaim, String heroLead, String heroPhoto, String storyTitle, String storyText,
            List<DemoOwner> owners, List<DemoStaff> staff, DemoMenuHero menuHero) {
    }

    record DemoHours(int isoDay, String opensAt, String closesAt) {
    }

    record DemoAnnouncement(String text, String tone, boolean active) {
    }

    record Manifest(List<DemoMedia> media, List<DemoCategory> categories, List<DemoItem> items, DemoContent content,
            List<DemoHours> hours, List<DemoAnnouncement> announcements) {
    }

    private final DemoProperties properties;
    private final MediaService media;
    private final MenuService menu;
    private final ContentService content;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditLog audit;

    DemoContentLoader(DemoProperties properties, MediaService media, MenuService menu, ContentService content,
            JdbcClient jdbc, ObjectMapper json, AuditLog audit) {
        this.properties = properties;
        this.media = media;
        this.menu = menu;
        this.content = content;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!databaseIsEmpty()) {
            log.info("Contenuti demo: database già popolato, nessun caricamento");
            return;
        }
        Manifest manifest;
        try (InputStream in = new ClassPathResource("demo/contenuti-demo.json").getInputStream()) {
            manifest = json.readValue(in, Manifest.class);
        }
        long start = System.nanoTime();
        Map<String, UUID> photos = importPhotos(manifest.media());
        Map<String, UUID> categories = new LinkedHashMap<>();
        for (DemoCategory c : manifest.categories()) {
            categories.put(c.slug(), menu.createCategory(null, new MenuService.CategoryCommand(c.slug(), c.name(), c.hint(), true)).id());
        }
        for (DemoItem i : manifest.items()) {
            menu.createDemoItem(new MenuService.ItemCommand(categories.get(i.category()), i.name(), i.description(),
                    i.ingredients(), null, false, Set.of(), photos.get(i.photo()), i.visible()), i.demoPriceCents());
        }
        DemoContent c = manifest.content();
        content.updateContent(null, content.content().version(), new ContentService.SiteContentCommand(
                c.heroClaim(), c.heroLead(), photos.get(c.heroPhoto()), Map.of(), c.storyTitle(), c.storyText(), false,
                c.owners().stream().map(o -> new ContentService.OwnerCommand(null, o.name(), o.roleLabel(), o.description(),
                        o.motto(), o.color(), photos.get(o.front()), photos.get(o.back()), true)).toList(),
                false,
                c.staff().stream().map(s -> new ContentService.StaffCommand(null, s.name(), s.roleLabel(), photos.get(s.photo()), true)).toList(),
                false,
                c.menuHero().photos().stream().map(photos::get).filter(java.util.Objects::nonNull).toList(),
                c.menuHero().intervalSec()));
        ContentService.SettingsView s = content.settings();
        content.updateSettings(null, s.version(), new ContentService.SettingsCommand(s.addressLine(), s.addressConfirmed(),
                s.mapQuery(), s.mapMode(), s.phone(), s.email(), s.instagramUrl(), false,
                manifest.hours().stream().map(h -> h.opensAt() == null
                        ? new ContentService.HoursView(h.isoDay(), true, null, null)
                        : new ContentService.HoursView(h.isoDay(), false, LocalTime.parse(h.opensAt()), LocalTime.parse(h.closesAt())))
                        .toList()));
        for (DemoAnnouncement a : manifest.announcements()) {
            content.createAnnouncement(null, new ContentService.AnnouncementCommand(a.text(), a.tone(), a.active(), null));
        }
        content.markDemoContent();
        audit.record(null, AuditAction.DEMO_CONTENT_LOADED, "SITE_SETTINGS", "1",
                Map.of("photos", photos.size(), "items", manifest.items().size()));
        log.warn("Contenuti DEMO caricati ({} foto, {} voci di menu) in {} ms: database marcato come demo, "
                + "non utilizzabile in produzione", photos.size(), manifest.items().size(), (System.nanoTime() - start) / 1_000_000);
    }

    private boolean databaseIsEmpty() {
        return jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM media_assets) AND NOT EXISTS (SELECT 1 FROM menu_categories)
                       AND NOT (SELECT demo_content FROM site_settings WHERE id = 1)
                """).query(Boolean.class).single();
    }

    private Map<String, UUID> importPhotos(List<DemoMedia> list) throws IOException {
        Path dir = Path.of(properties.assetsDir()).toAbsolutePath().normalize();
        Map<String, UUID> ids = new LinkedHashMap<>();
        List<UUID> order = new ArrayList<>();
        for (DemoMedia m : list) {
            Path file = dir.resolve(m.file()).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                log.warn("Contenuti demo: foto mancante {}", file);
                continue;
            }
            UUID id = media.upload(null, Files.readAllBytes(file), m.alt(), null).id();
            if (m.inGallery()) {
                media.update(null, id, null, null, true);
            }
            ids.put(m.key(), id);
            order.add(id);
        }
        media.reorder(null, order);
        return ids;
    }
}
