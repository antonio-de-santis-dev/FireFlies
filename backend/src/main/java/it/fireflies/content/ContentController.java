package it.fireflies.content;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.security.GestoreDetails;

/**
 * Contenuti del sito: lettura pubblica {@code GET /api/v1/public/site}; testi e impostazioni solo titolare
 * ({@code /admin/site-content}, {@code /admin/site-settings}); avvisi staff e titolare ({@code /admin/announcements}).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ContentController {

    record OwnerRequest(UUID id, String name, String roleLabel, String description, String motto, String color,
            UUID frontMediaId, UUID backMediaId, @NotNull Boolean visible) {
    }

    record StaffRequest(UUID id, String name, String roleLabel, UUID mediaId, @NotNull Boolean visible) {
    }

    record SiteContentRequest(@NotNull Integer version, String heroClaim, String heroLead, UUID heroMediaId,
            @Size(max = 20) Map<String, Boolean> sections, String storyTitle, String storyText,
            @NotNull Boolean storyConfirmed, @Size(max = 6) List<@Valid @NotNull OwnerRequest> owners,
            @NotNull Boolean ownersConfirmed, @Size(max = 12) List<@Valid @NotNull StaffRequest> staff,
            @NotNull Boolean staffConfirmed, @Size(max = 10) List<@NotNull UUID> menuHeroMediaIds,
            @NotNull Integer menuHeroIntervalSec) {
    }

    record HoursRequest(@NotNull Integer isoDay, @NotNull Boolean closed, LocalTime opensAt, LocalTime closesAt) {
    }

    record SettingsRequest(@NotNull Integer version, String addressLine, @NotNull Boolean addressConfirmed,
            String mapQuery, String mapMode, String phone, String email, String instagramUrl,
            @NotNull Boolean hoursConfirmed, @Size(max = 7) List<@Valid @NotNull HoursRequest> hours) {
    }

    record AnnouncementRequest(String text, String tone, @NotNull Boolean active, LocalDate visibleUntil) {

        ContentService.AnnouncementCommand command() {
            return new ContentService.AnnouncementCommand(text, tone, active, visibleUntil);
        }
    }

    private final ContentService content;

    ContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/api/v1/public/site")
    ResponseEntity<ContentService.PublicSite> publicSite() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(content.publicSite());
    }

    @GetMapping("/api/v1/admin/site-content")
    @PreAuthorize("hasRole('OWNER')")
    ContentService.SiteContentView siteContent() {
        return content.content();
    }

    @PutMapping("/api/v1/admin/site-content")
    @PreAuthorize("hasRole('OWNER')")
    ContentService.SiteContentView updateSiteContent(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody SiteContentRequest body) {
        return content.updateContent(actor.id(), body.version(), new ContentService.SiteContentCommand(
                body.heroClaim(), body.heroLead(), body.heroMediaId(), body.sections(), body.storyTitle(), body.storyText(),
                body.storyConfirmed(),
                body.owners() == null ? List.of() : body.owners().stream().map(o -> new ContentService.OwnerCommand(o.id(),
                        o.name(), o.roleLabel(), o.description(), o.motto(), o.color(), o.frontMediaId(), o.backMediaId(),
                        o.visible())).toList(),
                body.ownersConfirmed(),
                body.staff() == null ? List.of() : body.staff().stream().map(s -> new ContentService.StaffCommand(s.id(),
                        s.name(), s.roleLabel(), s.mediaId(), s.visible())).toList(),
                body.staffConfirmed(), body.menuHeroMediaIds(), body.menuHeroIntervalSec()));
    }

    @GetMapping("/api/v1/admin/site-settings")
    @PreAuthorize("hasRole('OWNER')")
    ContentService.SettingsView settings() {
        return content.settings();
    }

    @PutMapping("/api/v1/admin/site-settings")
    @PreAuthorize("hasRole('OWNER')")
    ContentService.SettingsView updateSettings(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody SettingsRequest body) {
        return content.updateSettings(actor.id(), body.version(), new ContentService.SettingsCommand(body.addressLine(),
                body.addressConfirmed(), body.mapQuery(), body.mapMode(), body.phone(), body.email(), body.instagramUrl(),
                body.hoursConfirmed(), body.hours() == null ? null : body.hours().stream()
                        .map(h -> new ContentService.HoursView(h.isoDay(), h.closed(), h.opensAt(), h.closesAt())).toList()));
    }

    @GetMapping("/api/v1/admin/announcements")
    @PreAuthorize("hasRole('STAFF')")
    List<ContentService.AnnouncementView> announcements() {
        return content.announcements();
    }

    @PostMapping("/api/v1/admin/announcements")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<ContentService.AnnouncementView> createAnnouncement(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody AnnouncementRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(content.createAnnouncement(actor.id(), body.command()));
    }

    @PutMapping("/api/v1/admin/announcements/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ContentService.AnnouncementView updateAnnouncement(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody AnnouncementRequest body) {
        return content.updateAnnouncement(actor.id(), id, body.command());
    }

    @DeleteMapping("/api/v1/admin/announcements/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> deleteAnnouncement(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        content.deleteAnnouncement(actor.id(), id);
        return ResponseEntity.noContent().build();
    }
}
