package it.fireflies.menu;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL del menu: {@code menu_categories}, {@code menu_items}, {@code menu_item_allergens}, {@code allergens}. */
@Repository
class MenuStore {

    record CategoryRow(UUID id, String slug, String name, String hint, int sortOrder, boolean visible, int itemCount) {
    }

    record ItemRow(UUID id, UUID categoryId, String name, String description, String ingredients, Integer priceCents,
            Integer demoPriceCents, UUID photoMediaId, boolean allergensDeclared, Instant allergensVerifiedAt,
            boolean visible, int sortOrder, int version, Instant updatedAt, Set<String> allergenCodes) {
    }

    record ItemData(UUID categoryId, String name, String description, String ingredients, Integer priceCents,
            UUID photoMediaId, boolean allergensDeclared, Set<String> allergenCodes, boolean visible) {
    }

    record Allergen(String code, String name) {
    }

    private static final String ITEM_COLUMNS = """
            i.id, i.category_id, i.name, i.description, i.ingredients, i.price_cents, i.demo_price_cents,
            i.photo_media_id, i.allergens_status, i.allergens_verified_at, i.visible, i.sort_order, i.version, i.updated_at
            """;

    private final JdbcClient jdbc;

    MenuStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Allergen> allergens() {
        return jdbc.sql("SELECT code, name_it FROM allergens ORDER BY sort_order")
                .query((rs, n) -> new Allergen(rs.getString(1), rs.getString(2))).list();
    }

    List<CategoryRow> categories() {
        return jdbc.sql("""
                SELECT c.id, c.slug, c.name, c.hint, c.sort_order, c.visible,
                       (SELECT count(*) FROM menu_items i WHERE i.category_id = c.id) AS items
                  FROM menu_categories c ORDER BY c.sort_order, c.name
                """).query(MenuStore::category).list();
    }

    Optional<CategoryRow> lockCategory(UUID id) {
        return jdbc.sql("""
                SELECT c.id, c.slug, c.name, c.hint, c.sort_order, c.visible,
                       (SELECT count(*) FROM menu_items i WHERE i.category_id = c.id) AS items
                  FROM menu_categories c WHERE c.id = ? FOR UPDATE
                """).param(id).query(MenuStore::category).optional();
    }

    boolean categoryExists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM menu_categories WHERE id = ?)").param(id).query(Boolean.class).single();
    }

    UUID insertCategory(String slug, String name, String hint, boolean visible) {
        return jdbc.sql("""
                INSERT INTO menu_categories (slug, name, hint, visible, sort_order)
                VALUES (?, ?, ?, ?, (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_categories))
                RETURNING id
                """).params(slug, name, hint, visible).query(UUID.class).single();
    }

    void updateCategory(UUID id, String slug, String name, String hint, boolean visible) {
        jdbc.sql("UPDATE menu_categories SET slug = ?, name = ?, hint = ?, visible = ? WHERE id = ?")
                .params(slug, name, hint, visible, id).update();
    }

    void deleteCategory(UUID id) {
        jdbc.sql("DELETE FROM menu_categories WHERE id = ?").param(id).update();
    }

    void reorderCategories(List<UUID> ordered) {
        List<UUID> all = jdbc.sql("SELECT id FROM menu_categories ORDER BY sort_order, name FOR UPDATE").query(UUID.class).list();
        List<UUID> next = new ArrayList<>(ordered.stream().filter(all::contains).distinct().toList());
        all.stream().filter(id -> !next.contains(id)).forEach(next::add);
        for (int i = 0; i < next.size(); i++) {
            jdbc.sql("UPDATE menu_categories SET sort_order = ? WHERE id = ?").params(i + 1, next.get(i)).update();
        }
    }

    List<ItemRow> items() {
        return withAllergens(jdbc.sql("SELECT " + ITEM_COLUMNS + """
                  FROM menu_items i JOIN menu_categories c ON c.id = i.category_id
                 ORDER BY c.sort_order, i.sort_order, i.name
                """).query(MenuStore::item).list());
    }

    Optional<ItemRow> item(UUID id) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM menu_items i WHERE i.id = ?").param(id)
                .query(MenuStore::item).optional().map(i -> withAllergens(List.of(i)).getFirst());
    }

    Optional<ItemRow> lockItem(UUID id) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM menu_items i WHERE i.id = ? FOR UPDATE").param(id)
                .query(MenuStore::item).optional().map(i -> withAllergens(List.of(i)).getFirst());
    }

    UUID insertItem(ItemData d, Integer demoPriceCents, UUID actor, Instant now) {
        UUID id = jdbc.sql("""
                INSERT INTO menu_items (category_id, name, description, ingredients, price_cents, demo_price_cents,
                                        photo_media_id, allergens_status, allergens_verified_at, allergens_verified_by,
                                        visible, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_items WHERE category_id = ?), ?, ?)
                RETURNING id
                """)
                .params(d.categoryId(), d.name(), d.description(), d.ingredients(), d.priceCents(), demoPriceCents,
                        d.photoMediaId(), d.allergensDeclared() ? "DECLARED" : "UNDECLARED",
                        d.allergensDeclared() ? utc(now) : null, d.allergensDeclared() ? actor : null,
                        d.visible(), d.categoryId(), utc(now), utc(now))
                .query(UUID.class).single();
        replaceAllergens(id, d.allergenCodes());
        return id;
    }

    /**
     * Aggiorna la voce solo se la versione è quella letta. La verifica degli allergeni (chi e quando) si rinnova
     * quando cambiano: {@code verified} indica se l'azione di questo gestore è una nuova verifica.
     */
    boolean updateItem(UUID id, int version, ItemData d, boolean verified, UUID actor, Instant now) {
        int rows = jdbc.sql("""
                UPDATE menu_items
                   SET category_id = ?, name = ?, description = ?, ingredients = ?, price_cents = ?, photo_media_id = ?,
                       allergens_status = ?,
                       allergens_verified_at = CASE WHEN ? THEN ? WHEN ? = 'UNDECLARED' THEN NULL ELSE allergens_verified_at END,
                       allergens_verified_by = CASE WHEN ? THEN ? WHEN ? = 'UNDECLARED' THEN NULL ELSE allergens_verified_by END,
                       visible = ?,
                       sort_order = CASE WHEN category_id = ? THEN sort_order
                                         ELSE (SELECT coalesce(max(sort_order), 0) + 1 FROM menu_items WHERE category_id = ?) END,
                       version = version + 1, updated_at = ?
                 WHERE id = ? AND version = ?
                """)
                .params(d.categoryId(), d.name(), d.description(), d.ingredients(), d.priceCents(), d.photoMediaId(),
                        status(d), verified, utc(now), status(d), verified, actor, status(d), d.visible(),
                        d.categoryId(), d.categoryId(), utc(now), id, version)
                .update();
        if (rows == 1) {
            replaceAllergens(id, d.allergenCodes());
        }
        return rows == 1;
    }

    boolean updateVisibility(UUID id, int version, boolean visible, Instant now) {
        return jdbc.sql("UPDATE menu_items SET visible = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?")
                .params(visible, utc(now), id, version).update() == 1;
    }

    void deleteItem(UUID id) {
        jdbc.sql("DELETE FROM menu_items WHERE id = ?").param(id).update();
    }

    private void replaceAllergens(UUID itemId, Set<String> codes) {
        jdbc.sql("DELETE FROM menu_item_allergens WHERE menu_item_id = ?").param(itemId).update();
        for (String code : codes) {
            jdbc.sql("INSERT INTO menu_item_allergens (menu_item_id, allergen_code) VALUES (?, ?)").params(itemId, code).update();
        }
    }

    private List<ItemRow> withAllergens(List<ItemRow> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<UUID, Set<String>> codes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT ia.menu_item_id, ia.allergen_code FROM menu_item_allergens ia
                  JOIN allergens a ON a.code = ia.allergen_code
                 WHERE ia.menu_item_id = ANY (?) ORDER BY a.sort_order
                """)
                .param(items.stream().map(ItemRow::id).toArray(UUID[]::new))
                .query((ResultSet rs) -> {
                    codes.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.LinkedHashSet<>()).add(rs.getString(2));
                });
        return items.stream().map(i -> new ItemRow(i.id(), i.categoryId(), i.name(), i.description(), i.ingredients(),
                i.priceCents(), i.demoPriceCents(), i.photoMediaId(), i.allergensDeclared(), i.allergensVerifiedAt(),
                i.visible(), i.sortOrder(), i.version(), i.updatedAt(), codes.getOrDefault(i.id(), Set.of()))).toList();
    }

    private static String status(ItemData d) {
        return d.allergensDeclared() ? "DECLARED" : "UNDECLARED";
    }

    private static CategoryRow category(ResultSet rs, int row) throws SQLException {
        return new CategoryRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("hint"), rs.getInt("sort_order"), rs.getBoolean("visible"), rs.getInt("items"));
    }

    private static ItemRow item(ResultSet rs, int row) throws SQLException {
        OffsetDateTime verified = rs.getObject("allergens_verified_at", OffsetDateTime.class);
        return new ItemRow(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("ingredients"), (Integer) rs.getObject("price_cents"),
                (Integer) rs.getObject("demo_price_cents"), rs.getObject("photo_media_id", UUID.class),
                "DECLARED".equals(rs.getString("allergens_status")), verified == null ? null : verified.toInstant(),
                rs.getBoolean("visible"), rs.getInt("sort_order"), rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), Set.of());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
