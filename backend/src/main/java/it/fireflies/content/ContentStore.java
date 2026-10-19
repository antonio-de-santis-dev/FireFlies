package it.fireflies.content;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL dei contenuti del sito: righe uniche {@code site_content}/{@code site_settings} e le tabelle collegate. */
@Repository
class ContentStore {

    record ContentRow(int version, String heroClaim, String heroLead, UUID heroMediaId, String storyTitle,
            String storyText, boolean storyConfirmed, boolean ownersConfirmed, boolean staffConfirmed) {
    }

    record OwnerRow(UUID id, String name, String roleLabel, String description, String motto, String color,
            UUID frontMediaId, UUID backMediaId, int sortOrder, boolean visible) {
    }

    record StaffRow(UUID id, String name, String roleLabel, UUID mediaId, int sortOrder, boolean visible) {
    }

    record SettingsRow(int version, String addressLine, boolean addressConfirmed, String mapQuery, String mapMode,
            String phone, String email, String instagramUrl, boolean hoursConfirmed, int menuHeroIntervalSec,
            boolean demoContent) {
    }

    record HoursRow(int isoDay, boolean closed, LocalTime opensAt, LocalTime closesAt, boolean closesNextDay) {
    }

    record AnnouncementRow(UUID id, String text, String tone, boolean active, Instant visibleUntil, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    ContentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- site_content -------------------------------------------------------------------------------------------

    ContentRow content(boolean lock) {
        return jdbc.sql("""
                SELECT version, hero_claim, hero_lead, hero_media_id, story_title, story_text, story_confirmed,
                       owners_confirmed, staff_confirmed
                  FROM site_content WHERE id = 1
                """ + (lock ? " FOR UPDATE" : ""))
                .query((rs, n) -> new ContentRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getBoolean(7),
                        rs.getBoolean(8), rs.getBoolean(9)))
                .single();
    }

    boolean updateContent(int version, ContentRow c) {
        return jdbc.sql("""
                UPDATE site_content
                   SET hero_claim = ?, hero_lead = ?, hero_media_id = ?, story_title = ?, story_text = ?,
                       story_confirmed = ?, owners_confirmed = ?, staff_confirmed = ?, version = version + 1, updated_at = now()
                 WHERE id = 1 AND version = ?
                """)
                .params(c.heroClaim(), c.heroLead(), c.heroMediaId(), c.storyTitle(), c.storyText(), c.storyConfirmed(),
                        c.ownersConfirmed(), c.staffConfirmed(), version)
                .update() == 1;
    }

    Map<String, Boolean> sections() {
        Map<String, Boolean> sections = new LinkedHashMap<>();
        jdbc.sql("SELECT code, enabled FROM site_sections ORDER BY sort_order, code")
                .query((ResultSet rs) -> {
                    sections.put(rs.getString(1), rs.getBoolean(2));
                });
        return sections;
    }

    void updateSection(String code, boolean enabled) {
        jdbc.sql("UPDATE site_sections SET enabled = ? WHERE code = ?").params(enabled, code).update();
    }

    List<OwnerRow> owners() {
        return jdbc.sql("""
                SELECT id, name, role_label, description, motto, color_token, front_media_id, back_media_id, sort_order, visible
                  FROM owners ORDER BY sort_order, name
                """).query(ContentStore::owner).list();
    }

    void replaceOwners(List<OwnerRow> owners) {
        List<UUID> keep = owners.stream().map(OwnerRow::id).filter(java.util.Objects::nonNull).toList();
        jdbc.sql("DELETE FROM owners WHERE NOT (id = ANY (?))").param(keep.toArray(UUID[]::new)).update();
        for (OwnerRow o : owners) {
            int updated = o.id() == null ? 0 : jdbc.sql("""
                    UPDATE owners SET name = ?, role_label = ?, description = ?, motto = ?, color_token = ?,
                                      front_media_id = ?, back_media_id = ?, sort_order = ?, visible = ?
                     WHERE id = ?
                    """)
                    .params(o.name(), o.roleLabel(), o.description(), o.motto(), o.color(), o.frontMediaId(),
                            o.backMediaId(), o.sortOrder(), o.visible(), o.id())
                    .update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO owners (name, role_label, description, motto, color_token, front_media_id,
                                            back_media_id, sort_order, visible)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                        .params(o.name(), o.roleLabel(), o.description(), o.motto(), o.color(), o.frontMediaId(),
                                o.backMediaId(), o.sortOrder(), o.visible())
                        .update();
            }
        }
    }

    List<StaffRow> staff() {
        return jdbc.sql("SELECT id, name, role_label, media_id, sort_order, visible FROM staff_members ORDER BY sort_order, name")
                .query((rs, n) -> new StaffRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getInt(5), rs.getBoolean(6)))
                .list();
    }

    void replaceStaff(List<StaffRow> staff) {
        List<UUID> keep = staff.stream().map(StaffRow::id).filter(java.util.Objects::nonNull).toList();
        jdbc.sql("DELETE FROM staff_members WHERE NOT (id = ANY (?))").param(keep.toArray(UUID[]::new)).update();
        for (StaffRow s : staff) {
            int updated = s.id() == null ? 0 : jdbc.sql("""
                    UPDATE staff_members SET name = ?, role_label = ?, media_id = ?, sort_order = ?, visible = ? WHERE id = ?
                    """)
                    .params(s.name(), s.roleLabel(), s.mediaId(), s.sortOrder(), s.visible(), s.id()).update();
            if (updated == 0) {
                jdbc.sql("INSERT INTO staff_members (name, role_label, media_id, sort_order, visible) VALUES (?, ?, ?, ?, ?)")
                        .params(s.name(), s.roleLabel(), s.mediaId(), s.sortOrder(), s.visible()).update();
            }
        }
    }

    List<UUID> heroSlides(String page) {
        return jdbc.sql("SELECT media_id FROM hero_slides WHERE page = ? AND enabled ORDER BY sort_order")
                .param(page).query(UUID.class).list();
    }

    void replaceHeroSlides(String page, List<UUID> mediaIds) {
        jdbc.sql("DELETE FROM hero_slides WHERE page = ?").param(page).update();
        for (int i = 0; i < mediaIds.size(); i++) {
            jdbc.sql("INSERT INTO hero_slides (page, media_id, sort_order) VALUES (?, ?, ?)")
                    .params(page, mediaIds.get(i), i + 1).update();
        }
    }

    // --- site_settings e orari ----------------------------------------------------------------------------------

    SettingsRow settings(boolean lock) {
        return jdbc.sql("""
                SELECT version, address_line, address_confirmed, map_query, map_mode, phone_public, email_public,
                       instagram_url, hours_confirmed, menu_hero_interval_sec, demo_content
                  FROM site_settings WHERE id = 1
                """ + (lock ? " FOR UPDATE" : ""))
                .query((rs, n) -> new SettingsRow(rs.getInt(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9),
                        rs.getInt(10), rs.getBoolean(11)))
                .single();
    }

    boolean updateSettings(int version, SettingsRow s, UUID actor) {
        return jdbc.sql("""
                UPDATE site_settings
                   SET address_line = ?, address_confirmed = ?, map_query = ?, map_mode = ?, phone_public = ?,
                       email_public = ?, instagram_url = ?, hours_confirmed = ?, version = version + 1,
                       updated_by = ?, updated_at = now()
                 WHERE id = 1 AND version = ?
                """)
                .params(s.addressLine(), s.addressConfirmed(), s.mapQuery(), s.mapMode(), s.phone(), s.email(),
                        s.instagramUrl(), s.hoursConfirmed(), actor, version)
                .update() == 1;
    }

    void updateMenuHeroInterval(int seconds) {
        jdbc.sql("UPDATE site_settings SET menu_hero_interval_sec = ? WHERE id = 1").param(seconds).update();
    }

    void markDemoContent() {
        jdbc.sql("UPDATE site_settings SET demo_content = true WHERE id = 1").update();
    }

    List<HoursRow> hours() {
        return jdbc.sql("SELECT iso_day, closed, opens_at, closes_at, closes_next_day FROM opening_hours ORDER BY iso_day, opens_at")
                .query((rs, n) -> new HoursRow(rs.getInt(1), rs.getBoolean(2), rs.getObject(3, LocalTime.class),
                        rs.getObject(4, LocalTime.class), rs.getBoolean(5)))
                .list();
    }

    void replaceHours(List<HoursRow> hours) {
        jdbc.sql("DELETE FROM opening_hours").update();
        for (HoursRow h : hours) {
            jdbc.sql("INSERT INTO opening_hours (iso_day, opens_at, closes_at, closes_next_day, closed) VALUES (?, ?, ?, ?, ?)")
                    .params(h.isoDay(), h.opensAt(), h.closesAt(), h.closesNextDay(), h.closed()).update();
        }
    }

    // --- announcements ------------------------------------------------------------------------------------------

    List<AnnouncementRow> announcements() {
        return jdbc.sql("SELECT id, text, tone, active, visible_until, created_at FROM announcements ORDER BY created_at DESC, id")
                .query(ContentStore::announcement).list();
    }

    List<AnnouncementRow> liveAnnouncements(Instant now) {
        return jdbc.sql("""
                SELECT id, text, tone, active, visible_until, created_at FROM announcements
                 WHERE active AND (visible_until IS NULL OR visible_until > ?)
                 ORDER BY created_at DESC, id
                """).param(utc(now)).query(ContentStore::announcement).list();
    }

    Optional<AnnouncementRow> announcement(UUID id) {
        return jdbc.sql("SELECT id, text, tone, active, visible_until, created_at FROM announcements WHERE id = ?")
                .param(id).query(ContentStore::announcement).optional();
    }

    UUID insertAnnouncement(String text, String tone, boolean active, Instant visibleUntil, UUID actor) {
        return jdbc.sql("INSERT INTO announcements (text, tone, active, visible_until, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(text, tone, active, visibleUntil == null ? null : utc(visibleUntil), actor).query(UUID.class).single();
    }

    boolean updateAnnouncement(UUID id, String text, String tone, boolean active, Instant visibleUntil) {
        return jdbc.sql("UPDATE announcements SET text = ?, tone = ?, active = ?, visible_until = ? WHERE id = ?")
                .params(text, tone, active, visibleUntil == null ? null : utc(visibleUntil), id).update() == 1;
    }

    boolean deleteAnnouncement(UUID id) {
        return jdbc.sql("DELETE FROM announcements WHERE id = ?").param(id).update() == 1;
    }

    private static OwnerRow owner(ResultSet rs, int row) throws SQLException {
        return new OwnerRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role_label"),
                rs.getString("description"), rs.getString("motto"), rs.getString("color_token"),
                rs.getObject("front_media_id", UUID.class), rs.getObject("back_media_id", UUID.class),
                rs.getInt("sort_order"), rs.getBoolean("visible"));
    }

    private static AnnouncementRow announcement(ResultSet rs, int row) throws SQLException {
        OffsetDateTime until = rs.getObject("visible_until", OffsetDateTime.class);
        return new AnnouncementRow(rs.getObject("id", UUID.class), rs.getString("text"), rs.getString("tone"),
                rs.getBoolean("active"), until == null ? null : until.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
