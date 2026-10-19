package it.fireflies.media;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL della libreria foto ({@code media_assets}, {@code media_variants}, {@code media_usages_v}). */
@Repository
class MediaStore {

    record VariantRow(String size, String storageKey, int width, int height, int bytes, String mimeType) {
    }

    record AssetRow(UUID id, String storageKey, String mimeType, int bytes, int width, int height, String altText,
            String credit, boolean inGallery, Integer galleryOrder, Instant createdAt, List<VariantRow> variants) {
    }

    record Usage(String type, UUID objectId, String label) {
    }

    private static final String ASSET_COLUMNS = """
            a.id, a.storage_key, a.mime_type, a.bytes, a.width, a.height, a.alt_text, a.credit, a.in_gallery,
            a.gallery_order, a.created_at
            """;

    private final JdbcClient jdbc;

    MediaStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertReady(UUID id, String storageKey, ImageProcessor.Processed image, String altText, String credit,
            UUID uploadedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO media_assets (id, storage_key, status, mime_type, bytes, width, height, sha256, alt_text,
                                          credit, uploaded_by, created_at, ready_at)
                VALUES (?, ?, 'READY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, storageKey, image.sourceMimeType(), image.bytes(), image.width(), image.height(),
                        image.sha256(), altText, credit, uploadedBy, utc(now), utc(now))
                .update();
        insertVariants(id, storageKey, image.variants());
    }

    void insertVariants(UUID mediaId, String storageKey, List<ImageProcessor.Variant> variants) {
        for (ImageProcessor.Variant v : variants) {
            jdbc.sql("""
                    INSERT INTO media_variants (media_id, variant, storage_key, width, height, bytes, mime_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(mediaId, v.size().name(), storageKey + "/" + v.fileName(), v.width(), v.height(),
                            v.bytes().length, v.mimeType())
                    .update();
        }
    }

    /** Sostituisce il file mantenendo id, testo alternativo e usi: chi usa la foto vede subito quella nuova. */
    void replaceFile(UUID id, String storageKey, ImageProcessor.Processed image, Instant now) {
        jdbc.sql("DELETE FROM media_variants WHERE media_id = ?").param(id).update();
        jdbc.sql("""
                UPDATE media_assets
                   SET storage_key = ?, mime_type = ?, bytes = ?, width = ?, height = ?, sha256 = ?, ready_at = ?
                 WHERE id = ?
                """)
                .params(storageKey, image.sourceMimeType(), image.bytes(), image.width(), image.height(),
                        image.sha256(), utc(now), id)
                .update();
        insertVariants(id, storageKey, image.variants());
    }

    Optional<AssetRow> lock(UUID id) {
        return jdbc.sql("SELECT " + ASSET_COLUMNS + " FROM media_assets a WHERE a.id = ? AND a.status = 'READY' FOR UPDATE")
                .param(id).query(MediaStore::asset).optional().map(this::withVariants);
    }

    Optional<AssetRow> find(UUID id) {
        return findAll(List.of(id)).stream().findFirst();
    }

    List<AssetRow> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AssetRow> assets = jdbc.sql("SELECT " + ASSET_COLUMNS
                        + " FROM media_assets a WHERE a.status = 'READY' AND a.id = ANY (?)")
                .param(ids.toArray(UUID[]::new)).query(MediaStore::asset).list();
        return attachVariants(assets);
    }

    /** Libreria nell'ordine scelto dai gestori; le foto mai ordinate in fondo, dalla più recente. */
    List<AssetRow> library() {
        return attachVariants(jdbc.sql("SELECT " + ASSET_COLUMNS + """
                 FROM media_assets a WHERE a.status = 'READY'
                ORDER BY a.gallery_order NULLS LAST, a.created_at DESC, a.id
                """).query(MediaStore::asset).list());
    }

    List<AssetRow> gallery() {
        return attachVariants(jdbc.sql("SELECT " + ASSET_COLUMNS + """
                 FROM media_assets a WHERE a.status = 'READY' AND a.in_gallery
                ORDER BY a.gallery_order NULLS LAST, a.created_at DESC, a.id
                """).query(MediaStore::asset).list());
    }

    void updateDetails(UUID id, String altText, String credit, boolean inGallery) {
        jdbc.sql("UPDATE media_assets SET alt_text = ?, credit = ?, in_gallery = ? WHERE id = ?")
                .params(altText, credit, inGallery, id).update();
    }

    /** Nuovo ordine: le foto elencate prima, nell'ordine dato; le altre dopo, nell'ordine che avevano. */
    void reorder(List<UUID> ordered) {
        List<UUID> all = jdbc.sql("""
                SELECT id FROM media_assets WHERE status = 'READY'
                 ORDER BY gallery_order NULLS LAST, created_at DESC, id FOR UPDATE
                """).query(UUID.class).list();
        List<UUID> next = new ArrayList<>(ordered.stream().filter(all::contains).distinct().toList());
        all.stream().filter(id -> !next.contains(id)).forEach(next::add);
        for (int i = 0; i < next.size(); i++) {
            jdbc.sql("UPDATE media_assets SET gallery_order = ? WHERE id = ?").params(i, next.get(i)).update();
        }
    }

    List<Usage> usages(UUID id) {
        return jdbc.sql("SELECT usage_type, object_id, label FROM media_usages_v WHERE media_id = ? ORDER BY usage_type, label")
                .param(id).query((rs, n) -> new Usage(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3))).list();
    }

    Map<UUID, List<Usage>> allUsages() {
        Map<UUID, List<Usage>> byMedia = new LinkedHashMap<>();
        jdbc.sql("SELECT media_id, usage_type, object_id, label FROM media_usages_v ORDER BY usage_type, label")
                .query((ResultSet rs) -> {
                    byMedia.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Usage(rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)));
                });
        return byMedia;
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM media_assets WHERE id = ?").param(id).update();
    }

    boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM media_assets WHERE id = ? AND status = 'READY')")
                .param(id).query(Boolean.class).single();
    }

    private AssetRow withVariants(AssetRow asset) {
        return attachVariants(List.of(asset)).getFirst();
    }

    private List<AssetRow> attachVariants(List<AssetRow> assets) {
        if (assets.isEmpty()) {
            return assets;
        }
        Map<UUID, List<VariantRow>> variants = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT media_id, variant, storage_key, width, height, bytes, mime_type
                  FROM media_variants WHERE media_id = ANY (?) ORDER BY width
                """)
                .param(assets.stream().map(AssetRow::id).toArray(UUID[]::new))
                .query((ResultSet rs) -> {
                    variants.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new VariantRow(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                                    rs.getInt(6), rs.getString(7)));
                });
        return assets.stream().map(a -> new AssetRow(a.id(), a.storageKey(), a.mimeType(), a.bytes(), a.width(),
                a.height(), a.altText(), a.credit(), a.inGallery(), a.galleryOrder(), a.createdAt(),
                List.copyOf(variants.getOrDefault(a.id(), List.of())))).toList();
    }

    private static AssetRow asset(ResultSet rs, int row) throws SQLException {
        return new AssetRow(rs.getObject("id", UUID.class), rs.getString("storage_key"), rs.getString("mime_type"),
                rs.getInt("bytes"), rs.getInt("width"), rs.getInt("height"), rs.getString("alt_text"),
                rs.getString("credit"), rs.getBoolean("in_gallery"), (Integer) rs.getObject("gallery_order"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), List.of());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
