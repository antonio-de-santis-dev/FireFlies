package it.fireflies.content;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.BusinessTime;
import it.fireflies.media.MediaCatalog;
import it.fireflies.media.MediaRef;

/**
 * Contenuti pubblici del sito (DATA_MODEL §3.7). Testi, titolari, staff e testata del menu si salvano insieme
 * («Salva e pubblica» della pagina Personalizza il sito) con una sola versione: niente pubblicazioni a metà.
 */
@Service
public class ContentService {

    public static final Set<String> OWNER_COLORS = Set.of("giallo", "arancio", "verde");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 ]{5,18}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern INSTAGRAM = Pattern.compile("^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$");

    public record OwnerView(UUID id, String name, String roleLabel, String description, String motto, String color,
            MediaRef front, MediaRef back, boolean visible) {
    }

    public record StaffView(UUID id, String name, String roleLabel, MediaRef photo, boolean visible) {
    }

    public record MenuHeroView(List<MediaRef> photos, int intervalSec) {
    }

    public record SiteContentView(int version, String heroClaim, String heroLead, MediaRef heroPhoto,
            Map<String, Boolean> sections, String storyTitle, String storyText, boolean storyConfirmed,
            List<OwnerView> owners, boolean ownersConfirmed, List<StaffView> staff, boolean staffConfirmed,
            MenuHeroView menuHero) {
    }

    public record HoursView(int isoDay, boolean closed, LocalTime opensAt, LocalTime closesAt) {
    }

    public record SettingsView(int version, String addressLine, boolean addressConfirmed, String mapQuery,
            String mapMode, String phone, String email, String instagramUrl, boolean hoursConfirmed, List<HoursView> hours) {
    }

    public record AnnouncementView(UUID id, String text, String tone, boolean active, LocalDate visibleUntil, Instant createdAt) {
    }

    public record PublicSite(SettingsView settings, SiteContentView content, List<AnnouncementView> announcements,
            boolean demoContent) {
    }

    public record OwnerCommand(UUID id, String name, String roleLabel, String description, String motto, String color,
            UUID frontMediaId, UUID backMediaId, boolean visible) {
    }

    public record StaffCommand(UUID id, String name, String roleLabel, UUID mediaId, boolean visible) {
    }

    public record SiteContentCommand(String heroClaim, String heroLead, UUID heroMediaId, Map<String, Boolean> sections,
            String storyTitle, String storyText, boolean storyConfirmed, List<OwnerCommand> owners, boolean ownersConfirmed,
            List<StaffCommand> staff, boolean staffConfirmed, List<UUID> menuHeroMediaIds, int menuHeroIntervalSec) {
    }

    public record SettingsCommand(String addressLine, boolean addressConfirmed, String mapQuery, String mapMode,
            String phone, String email, String instagramUrl, boolean hoursConfirmed, List<HoursView> hours,
            boolean confirmClosures) {

        public SettingsCommand(String addressLine, boolean addressConfirmed, String mapQuery, String mapMode, String phone,
                String email, String instagramUrl, boolean hoursConfirmed, List<HoursView> hours) {
            this(addressLine, addressConfirmed, mapQuery, mapMode, phone, email, instagramUrl, hoursConfirmed, hours, false);
        }
    }

    public record AnnouncementCommand(String text, String tone, boolean active, LocalDate visibleUntil) {
    }

    private final ContentStore store;
    private final MediaCatalog media;
    private final AuditLog audit;
    private final Clock clock;

    ContentService(ContentStore store, MediaCatalog media, AuditLog audit, Clock clock) {
        this.store = store;
        this.media = media;
        this.audit = audit;
        this.clock = clock;
    }

    // --- lettura ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SiteContentView content() {
        ContentStore.ContentRow c = store.content(false);
        List<ContentStore.OwnerRow> owners = store.owners();
        List<ContentStore.StaffRow> staff = store.staff();
        List<UUID> hero = store.heroSlides("MENU");
        Map<UUID, MediaRef> refs = media.refs(Stream.of(
                Stream.of(c.heroMediaId()),
                owners.stream().flatMap(o -> Stream.of(o.frontMediaId(), o.backMediaId())),
                staff.stream().map(ContentStore.StaffRow::mediaId),
                hero.stream()).flatMap(s -> s).filter(Objects::nonNull).toList());
        return new SiteContentView(c.version(), c.heroClaim(), c.heroLead(), refs.get(c.heroMediaId()), store.sections(),
                c.storyTitle(), c.storyText(), c.storyConfirmed(),
                owners.stream().map(o -> new OwnerView(o.id(), o.name(), o.roleLabel(), o.description(), o.motto(), o.color(),
                        refs.get(o.frontMediaId()), refs.get(o.backMediaId()), o.visible())).toList(),
                c.ownersConfirmed(),
                staff.stream().map(s -> new StaffView(s.id(), s.name(), s.roleLabel(), refs.get(s.mediaId()), s.visible())).toList(),
                c.staffConfirmed(),
                new MenuHeroView(hero.stream().map(refs::get).filter(Objects::nonNull).toList(),
                        store.settings(false).menuHeroIntervalSec()));
    }

    @Transactional(readOnly = true)
    public boolean hasDemoContent() {
        return store.settings(false).demoContent();
    }

    @Transactional(readOnly = true)
    public SettingsView settings() {
        ContentStore.SettingsRow s = store.settings(false);
        return new SettingsView(s.version(), s.addressLine(), s.addressConfirmed(), s.mapQuery(), s.mapMode(), s.phone(),
                s.email(), s.instagramUrl(), s.hoursConfirmed(), weekHours());
    }

    /** Tutto quello che serve al sito pubblico in una sola risposta (con ETag). Solo titolari e staff visibili. */
    @Transactional(readOnly = true)
    public PublicSite publicSite() {
        SiteContentView c = content();
        SiteContentView visible = new SiteContentView(c.version(), c.heroClaim(), c.heroLead(), c.heroPhoto(), c.sections(),
                c.storyTitle(), c.storyText(), c.storyConfirmed(),
                c.owners().stream().filter(OwnerView::visible).toList(), c.ownersConfirmed(),
                c.staff().stream().filter(StaffView::visible).toList(), c.staffConfirmed(), c.menuHero());
        return new PublicSite(settings(), visible,
                store.liveAnnouncements(Instant.now(clock)).stream().map(ContentService::view).toList(),
                store.settings(false).demoContent());
    }

    @Transactional(readOnly = true)
    public List<AnnouncementView> announcements() {
        return store.announcements().stream().map(ContentService::view).toList();
    }

    // --- scrittura (titolare) -----------------------------------------------------------------------------------

    @Transactional
    public SiteContentView updateContent(UUID actorId, int version, SiteContentCommand c) {
        ContentStore.ContentRow current = store.content(true);
        if (current.version() != version) {
            throw ApiErrors.versionConflict();
        }
        String claim = text(c.heroClaim(), "heroClaim", 3, 120, "La frase principale deve avere da 3 a 120 caratteri.");
        String lead = optionalText(c.heroLead(), "heroLead", 400);
        String storyTitle = optionalText(c.storyTitle(), "storyTitle", 120);
        String storyText = optionalText(c.storyText(), "storyText", 4000);
        media.requireReady(c.heroMediaId(), "heroMediaId");

        Map<String, Boolean> sections = c.sections() == null ? Map.of() : c.sections();
        Set<String> known = store.sections().keySet();
        sections.keySet().stream().filter(k -> !known.contains(k)).findFirst().ifPresent(k -> {
            throw ApiErrors.invalid("sections", "Sezione della home sconosciuta: " + k + ".");
        });

        List<OwnerCommand> ownerCommands = c.owners() == null ? List.of() : c.owners();
        if (ownerCommands.size() > 6) {
            throw ApiErrors.invalid("owners", "Al massimo 6 titolari.");
        }
        List<ContentStore.OwnerRow> owners = new ArrayList<>();
        for (int i = 0; i < ownerCommands.size(); i++) {
            OwnerCommand o = ownerCommands.get(i);
            String field = "owners[" + i + "]";
            if (o.color() != null && !OWNER_COLORS.contains(o.color())) {
                throw ApiErrors.invalid(field + ".color", "Colore del titolare non previsto.");
            }
            media.requireReady(o.frontMediaId(), field + ".frontMediaId");
            media.requireReady(o.backMediaId(), field + ".backMediaId");
            owners.add(new ContentStore.OwnerRow(o.id(),
                    text(o.name(), field + ".name", 1, 80, "Scrivi il nome del titolare " + (i + 1) + " (massimo 80 caratteri)."),
                    optionalText(o.roleLabel(), field + ".roleLabel", 80), optionalText(o.description(), field + ".description", 600),
                    optionalText(o.motto(), field + ".motto", 160), o.color(), o.frontMediaId(), o.backMediaId(), i + 1, o.visible()));
        }

        List<StaffCommand> staffCommands = c.staff() == null ? List.of() : c.staff();
        if (staffCommands.size() > 12) {
            throw ApiErrors.invalid("staff", "Al massimo 12 persone dello staff.");
        }
        List<ContentStore.StaffRow> staff = new ArrayList<>();
        for (int i = 0; i < staffCommands.size(); i++) {
            StaffCommand s = staffCommands.get(i);
            String field = "staff[" + i + "]";
            media.requireReady(s.mediaId(), field + ".mediaId");
            staff.add(new ContentStore.StaffRow(s.id(),
                    text(s.name(), field + ".name", 1, 80, "Scrivi il nome della persona " + (i + 1) + " dello staff."),
                    optionalText(s.roleLabel(), field + ".roleLabel", 80), s.mediaId(), i + 1, s.visible()));
        }

        List<UUID> hero = c.menuHeroMediaIds() == null ? List.of() : c.menuHeroMediaIds().stream().distinct().toList();
        if (hero.size() > 10) {
            throw ApiErrors.invalid("menuHero", "Al massimo 10 foto nella testata del menu.");
        }
        hero.forEach(id -> media.requireReady(id, "menuHero"));
        if (c.menuHeroIntervalSec() < 10 || c.menuHeroIntervalSec() > 600) {
            throw ApiErrors.invalid("menuHeroIntervalSec", "La rotazione deve essere tra 10 secondi e 10 minuti.");
        }

        store.updateContent(version, new ContentStore.ContentRow(version, claim, lead, c.heroMediaId(), storyTitle, storyText,
                c.storyConfirmed(), c.ownersConfirmed(), c.staffConfirmed()));
        sections.forEach(store::updateSection);
        store.replaceOwners(owners);
        store.replaceStaff(staff);
        store.replaceHeroSlides("MENU", hero);
        store.updateMenuHeroInterval(c.menuHeroIntervalSec());
        audit.record(actorId, AuditAction.SITE_CONTENT_UPDATED, "SITE_CONTENT", "1",
                Map.of("owners", owners.size(), "staff", staff.size(), "menuHeroPhotos", hero.size()));
        return content();
    }

    @Transactional
    public SettingsView updateSettings(UUID actorId, int version, SettingsCommand c) {
        ContentStore.SettingsRow current = store.settings(true);
        if (current.version() != version) {
            throw ApiErrors.versionConflict();
        }
        String address = text(c.addressLine(), "addressLine", 5, 160, "Scrivi l'indirizzo (da 5 a 160 caratteri).");
        String mapQuery = text(c.mapQuery(), "mapQuery", 3, 200, "Scrivi cosa cercare su Google Maps (da 3 a 200 caratteri).");
        if (!"CONSENT".equals(c.mapMode()) && !"AUTO".equals(c.mapMode())) {
            throw ApiErrors.invalid("mapMode", "Scegli quando caricare la mappa.");
        }
        String phone = optionalText(c.phone(), "phone", 20);
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw ApiErrors.invalid("phone", "Scrivi il telefono con sole cifre, es. +39 0836 000000.");
        }
        String email = optionalText(c.email(), "email", 254);
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw ApiErrors.invalid("email", "Scrivi un'email valida, es. info@esempio.it.");
        }
        String instagram = optionalText(c.instagramUrl(), "instagramUrl", 200);
        if (instagram != null && !INSTAGRAM.matcher(instagram).matches()) {
            throw ApiErrors.invalid("instagramUrl", "Incolla l'indirizzo del profilo, es. https://www.instagram.com/fireflies.aperitif/.");
        }
        List<ContentStore.HoursRow> hours = hours(c.hours());
        // FIX 2: un giorno della settimana che passa da aperto a chiuso con prenotazioni attive future si salva solo dopo
        // la conferma del gestore (confirmClosures); nessuna prenotazione viene annullata o rifiutata in automatico
        List<Integer> newlyClosed = newlyClosedDays(store.hours(), hours);
        if (!newlyClosed.isEmpty() && !c.confirmClosures()) {
            List<it.fireflies.dining.DiningStore.DayReservations> days =
                    store.activeOnWeekdays(BusinessTime.serviceDate(Instant.now(clock)), newlyClosed);
            if (!days.isEmpty()) {
                throw it.fireflies.dining.DiningService.closureHasReservations(days);
            }
        }

        store.updateSettings(version, new ContentStore.SettingsRow(version, address, c.addressConfirmed(), mapQuery, c.mapMode(),
                phone, email, instagram, c.hoursConfirmed(), current.menuHeroIntervalSec(), current.demoContent()), actorId);
        store.replaceHours(hours);
        audit.record(actorId, AuditAction.SITE_SETTINGS_UPDATED, "SITE_SETTINGS", "1",
                Map.of("hoursConfirmed", c.hoursConfirmed(), "addressConfirmed", c.addressConfirmed()));
        return settings();
    }

    /** Giorni (1 = lunedì) chiusi nel nuovo orario e aperti in quello attuale (stessa regola di DiningStore.weeklyClosed). */
    private static List<Integer> newlyClosedDays(List<ContentStore.HoursRow> before, List<ContentStore.HoursRow> after) {
        List<Integer> result = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if (weeklyClosed(after, day) && !weeklyClosed(before, day)) {
                result.add(day);
            }
        }
        return result;
    }

    private static boolean weeklyClosed(List<ContentStore.HoursRow> hours, int isoDay) {
        List<ContentStore.HoursRow> rows = hours.stream().filter(h -> h.isoDay() == isoDay).toList();
        return !rows.isEmpty() && rows.stream().allMatch(ContentStore.HoursRow::closed);
    }

    // --- avvisi (staff e titolare) ------------------------------------------------------------------------------

    @Transactional
    public AnnouncementView createAnnouncement(UUID actorId, AnnouncementCommand c) {
        Validated v = validate(c);
        UUID id = store.insertAnnouncement(v.text, v.tone, c.active(), v.until, actorId);
        audit.record(actorId, AuditAction.ANNOUNCEMENT_CREATED, "ANNOUNCEMENT", id.toString(), Map.of("active", c.active()));
        return view(store.announcement(id).orElseThrow());
    }

    @Transactional
    public AnnouncementView updateAnnouncement(UUID actorId, UUID id, AnnouncementCommand c) {
        Validated v = validate(c);
        if (!store.updateAnnouncement(id, v.text, v.tone, c.active(), v.until)) {
            throw ApiErrors.notFound("L'avviso non esiste più.");
        }
        audit.record(actorId, AuditAction.ANNOUNCEMENT_UPDATED, "ANNOUNCEMENT", id.toString(), Map.of("active", c.active()));
        return view(store.announcement(id).orElseThrow());
    }

    @Transactional
    public void deleteAnnouncement(UUID actorId, UUID id) {
        if (!store.deleteAnnouncement(id)) {
            throw ApiErrors.notFound("L'avviso non esiste più.");
        }
        audit.record(actorId, AuditAction.ANNOUNCEMENT_DELETED, "ANNOUNCEMENT", id.toString(), Map.of());
    }

    private record Validated(String text, String tone, Instant until) {
    }

    private Validated validate(AnnouncementCommand c) {
        String text = text(c.text(), "text", 5, 140, "Scrivi il testo dell'avviso (da 5 a 140 caratteri).");
        if (!"INFO".equals(c.tone()) && !"IMPORTANT".equals(c.tone())) {
            throw ApiErrors.invalid("tone", "Scegli il tipo di avviso.");
        }
        Instant until = null;
        if (c.visibleUntil() != null) {
            if (c.visibleUntil().isBefore(BusinessTime.serviceDate(Instant.now(clock)))) {
                throw ApiErrors.invalid("visibleUntil", "La data di fine è già passata.");
            }
            // «fino al 10/11» = visibile per tutto il 10 novembre, ora italiana
            until = c.visibleUntil().plusDays(1).atStartOfDay(BusinessTime.ZONE).toInstant();
        }
        return new Validated(text, c.tone(), until);
    }

    private static AnnouncementView view(ContentStore.AnnouncementRow a) {
        LocalDate until = a.visibleUntil() == null ? null
                : a.visibleUntil().atZone(BusinessTime.ZONE).toLocalDate().minusDays(1);
        return new AnnouncementView(a.id(), a.text(), a.tone(), a.active(), until, a.createdAt());
    }

    // --- orari --------------------------------------------------------------------------------------------------

    /** Sette giorni, lunedì = 1. Un giorno senza righe è mostrato come chiuso. */
    private List<HoursView> weekHours() {
        Map<Integer, ContentStore.HoursRow> byDay = new LinkedHashMap<>();
        store.hours().forEach(h -> byDay.putIfAbsent(h.isoDay(), h));
        List<HoursView> week = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            ContentStore.HoursRow h = byDay.get(day);
            week.add(h == null || h.closed() ? new HoursView(day, true, null, null)
                    : new HoursView(day, false, h.opensAt(), h.closesAt()));
        }
        return week;
    }

    private static List<ContentStore.HoursRow> hours(List<HoursView> hours) {
        if (hours == null || hours.size() != 7) {
            throw ApiErrors.invalid("hours", "Indica gli orari di tutti e sette i giorni.");
        }
        Set<Integer> days = new HashSet<>();
        List<ContentStore.HoursRow> rows = new ArrayList<>();
        for (HoursView h : hours) {
            if (h.isoDay() < 1 || h.isoDay() > 7 || !days.add(h.isoDay())) {
                throw ApiErrors.invalid("hours", "Ogni giorno della settimana deve comparire una volta sola.");
            }
            if (h.closed()) {
                rows.add(new ContentStore.HoursRow(h.isoDay(), true, null, null, false));
                continue;
            }
            if (h.opensAt() == null || h.closesAt() == null || h.opensAt().equals(h.closesAt())) {
                throw ApiErrors.invalid("hours", "Indica apertura e chiusura (diverse) per ogni giorno aperto.");
            }
            // chiusura «prima» dell'apertura = dopo mezzanotte (es. 18:00–02:00), P-24
            rows.add(new ContentStore.HoursRow(h.isoDay(), false, h.opensAt(), h.closesAt(), h.closesAt().isBefore(h.opensAt())));
        }
        return rows;
    }

    private static String text(String value, String field, int min, int max, String message) {
        String v = value == null ? "" : value.strip();
        if (v.length() < min || v.length() > max) {
            throw ApiErrors.invalid(field, message);
        }
        return v;
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiErrors.invalid(field, "Testo troppo lungo (massimo " + max + " caratteri).");
        }
        return v;
    }

    /** Usato solo dal caricatore demo del profilo local: il segnale blocca l'avvio in produzione (DemoDataGuard). */
    @Transactional
    public void markDemoContent() {
        store.markDemoContent();
    }
}
