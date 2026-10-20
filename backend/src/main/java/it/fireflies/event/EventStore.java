package it.fireflies.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL degli eventi, delle loro foto e delle prenotazioni collegate (solo letture utili ai controlli). */
@Repository
class EventStore {

    record Row(UUID id, String slug, String title, String kind, String status, Instant startsAt, Instant endsAt, UUID zoneId,
            boolean exclusive, String bookingMode, Integer capacityCovers, int confirmedCovers, String artist, String summary,
            String body, UUID posterMediaId, Instant createdAt, Instant updatedAt, int version) {

        boolean timed() {
            return startsAt != null && endsAt != null;
        }
    }

    record Values(String slug, String title, String kind, Instant startsAt, Instant endsAt, UUID zoneId, boolean exclusive,
            String bookingMode, Integer capacityCovers, String artist, String summary, String body, UUID posterMediaId) {
    }

    /** Prenotazione che si sovrappone a un evento, senza dati personali (riferimento, ora, persone, stato). */
    record Overlap(UUID id, String reference, Instant startsAt, Instant endsAt, int guests, String status) {
    }

    private static final String COLUMNS = """
            id, slug, title, kind, status, starts_at, ends_at, zone_id, exclusive, booking_mode, capacity_covers,
            confirmed_covers, artist, summary, body, poster_media_id, created_at, updated_at, version
            """;

    private final JdbcClient jdbc;

    EventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(Values v, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO events (slug, title, kind, starts_at, ends_at, zone_id, exclusive, booking_mode, capacity_covers,
                                    artist, summary, body, poster_media_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)
                .params(v.slug(), v.title(), v.kind(), utc(v.startsAt()), utc(v.endsAt()), v.zoneId(), v.exclusive(),
                        v.bookingMode(), v.capacityCovers(), v.artist(), v.summary(), v.body(), v.posterMediaId(), createdBy)
                .query(UUID.class).single();
    }

    void update(UUID id, Values v) {
        jdbc.sql("""
                UPDATE events SET slug = ?, title = ?, kind = ?, starts_at = ?, ends_at = ?, zone_id = ?, exclusive = ?,
                       booking_mode = ?, capacity_covers = ?, artist = ?, summary = ?, body = ?, poster_media_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                """)
                .params(v.slug(), v.title(), v.kind(), utc(v.startsAt()), utc(v.endsAt()), v.zoneId(), v.exclusive(),
                        v.bookingMode(), v.capacityCovers(), v.artist(), v.summary(), v.body(), v.posterMediaId(), id)
                .update();
    }

    void updateStatus(UUID id, String status) {
        jdbc.sql("UPDATE events SET status = ?, version = version + 1, updated_at = now() WHERE id = ?").params(status, id).update();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM events WHERE id = ?").param(id).update();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM events WHERE id = ?").param(id).query(EventStore::row).optional();
    }

    Optional<Row> bySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM events WHERE slug = ?").param(slug).query(EventStore::row).optional();
    }

    /** Lock della riga evento: secondo passo dell'ordine globale (prenotazione → evento → zona → quanti → tavoli). */
    Optional<Row> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM events WHERE id = ? FOR UPDATE").param(id).query(EventStore::row).optional();
    }

    boolean slugTaken(String slug, UUID except) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM events WHERE slug = ? AND id IS DISTINCT FROM ?)")
                .params(slug, except).query(Boolean.class).single();
    }

    List<Row> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM events ORDER BY starts_at NULLS FIRST, title").query(EventStore::row).list();
    }

    /** Pubblici: pubblicati o annullati, format senza data e serate non ancora finite. */
    List<Row> visible(Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM events
                WHERE status IN ('PUBLISHED', 'CANCELLED') AND (starts_at IS NULL OR coalesce(ends_at, starts_at) > ?)
                ORDER BY starts_at NULLS FIRST, title
                """).param(utc(now)).query(EventStore::row).list();
    }

    List<UUID> media(UUID eventId) {
        return jdbc.sql("SELECT media_id FROM event_media WHERE event_id = ? ORDER BY sort_order, media_id")
                .param(eventId).query(UUID.class).list();
    }

    void replaceMedia(UUID eventId, List<UUID> mediaIds) {
        jdbc.sql("DELETE FROM event_media WHERE event_id = ?").param(eventId).update();
        for (int i = 0; i < mediaIds.size(); i++) {
            jdbc.sql("INSERT INTO event_media (event_id, media_id, sort_order) VALUES (?, ?, ?)")
                    .params(eventId, mediaIds.get(i), i).update();
        }
    }

    /** Prenotazioni <b>ordinarie</b> nelle zone indicate che si sovrappongono all'intervallo, con lo stato richiesto. */
    List<Overlap> ordinaryOverlaps(List<UUID> zoneIds, Instant start, Instant end, List<String> statuses) {
        return jdbc.sql("""
                SELECT id, public_ref, starts_at, coalesce(occupied_until, ends_at), guest_count, status FROM reservations
                 WHERE event_id IS NULL AND capacity_zone_id = ANY (?) AND status = ANY (?)
                   AND starts_at < ? AND coalesce(occupied_until, ends_at) > ?
                 ORDER BY starts_at, public_ref
                """)
                .params(zoneIds.toArray(UUID[]::new), statuses.toArray(String[]::new), utc(end), utc(start))
                .query((rs, n) -> new Overlap(rs.getObject(1, UUID.class), rs.getString(2), instant(rs, 3), instant(rs, 4),
                        rs.getInt(5), rs.getString(6)))
                .list();
    }

    /** Prenotazioni dell'evento negli stati indicati, in ordine di id (ordine dei lock per le operazioni di massa). */
    List<UUID> reservationsOf(UUID eventId, List<String> statuses) {
        return jdbc.sql("SELECT id FROM reservations WHERE event_id = ? AND status = ANY (?) ORDER BY id")
                .params(eventId, statuses.toArray(String[]::new)).query(UUID.class).list();
    }

    long countReservations(UUID eventId, String status) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE event_id = ? AND status = ?").params(eventId, status)
                .query(Long.class).single();
    }

    long pendingCovers(UUID eventId) {
        return jdbc.sql("SELECT coalesce(sum(guest_count), 0) FROM reservations WHERE event_id = ? AND status = 'PENDING'")
                .param(eventId).query(Long.class).single();
    }

    boolean hasReservations(UUID eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservations WHERE event_id = ?)").param(eventId).query(Boolean.class).single();
    }

    /** Le richieste in attesa seguono l'orario dell'evento (non occupano posti, nessun controllo del registro). */
    void movePending(UUID eventId, Instant start, Instant end, java.time.LocalDate date) {
        jdbc.sql("""
                UPDATE reservations SET starts_at = ?, ends_at = ?, reservation_date = ?, version = version + 1, updated_at = now()
                 WHERE event_id = ? AND status = 'PENDING'
                """).params(utc(start), utc(end), date, eventId).update();
    }

    void addConfirmedCovers(UUID eventId, int delta) {
        jdbc.sql("UPDATE events SET confirmed_covers = confirmed_covers + ?, updated_at = now() WHERE id = ?")
                .params(delta, eventId).update();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"), rs.getString("kind"),
                rs.getString("status"), instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getObject("zone_id", UUID.class),
                rs.getBoolean("exclusive"), rs.getString("booking_mode"), (Integer) rs.getObject("capacity_covers"),
                rs.getInt("confirmed_covers"), rs.getString("artist"), rs.getString("summary"), rs.getString("body"),
                rs.getObject("poster_media_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
