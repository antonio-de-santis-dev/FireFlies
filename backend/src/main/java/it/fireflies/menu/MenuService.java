package it.fireflies.menu;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiErrors;
import it.fireflies.common.ApiException;
import it.fireflies.media.MediaCatalog;
import it.fireflies.media.MediaRef;

/**
 * Menu: categorie e voci con prezzo in centesimi ({@code null} = «Prezzo da definire») e allergeni del
 * Reg. UE 1169/2011. Gli allergeni sono «dichiarati» solo se un gestore li ha verificati: chi e quando restano salvati.
 */
@Service
public class MenuService {

    public static final int MAX_PRICE_CENTS = 100_000;

    public record AllergenView(String code, String name) {
    }

    public record AllergenInfo(boolean declared, List<String> codes, Instant verifiedAt) {
    }

    public record CategoryView(UUID id, String slug, String name, String hint, int sortOrder, boolean visible, int itemCount) {
    }

    public record ItemView(UUID id, UUID categoryId, String categorySlug, String name, String description,
            List<String> ingredients, Integer priceCents, Integer demoPriceCents, AllergenInfo allergens, MediaRef photo,
            boolean visible, int sortOrder, int version, Instant updatedAt) {
    }

    public record AdminMenu(List<CategoryView> categories, List<ItemView> items, List<AllergenView> allergens) {
    }

    public record PublicItem(UUID id, String name, String description, List<String> ingredients, Integer priceCents,
            Integer demoPriceCents, AllergenInfo allergens, MediaRef photo) {
    }

    public record PublicCategory(String slug, String name, String hint, List<PublicItem> items) {
    }

    public record PublicMenu(List<PublicCategory> categories, List<AllergenView> allergens) {
    }

    public record CategoryCommand(String slug, String name, String hint, boolean visible) {
    }

    public record ItemCommand(UUID categoryId, String name, String description, List<String> ingredients,
            Integer priceCents, boolean allergensDeclared, Set<String> allergenCodes, UUID photoMediaId, boolean visible) {
    }

    private final MenuStore store;
    private final MediaCatalog media;
    private final AuditLog audit;
    private final Clock clock;

    MenuService(MenuStore store, MediaCatalog media, AuditLog audit, Clock clock) {
        this.store = store;
        this.media = media;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminMenu adminMenu() {
        List<MenuStore.CategoryRow> categories = store.categories();
        List<MenuStore.ItemRow> items = store.items();
        Map<UUID, String> slugs = categories.stream().collect(Collectors.toMap(MenuStore.CategoryRow::id, MenuStore.CategoryRow::slug));
        Map<UUID, MediaRef> photos = media.refs(items.stream().map(MenuStore.ItemRow::photoMediaId).toList());
        return new AdminMenu(categories.stream().map(MenuService::view).toList(),
                items.stream().map(i -> view(i, slugs.get(i.categoryId()), photos.get(i.photoMediaId()))).toList(),
                allergens());
    }

    /** Solo categorie e voci visibili, nell'ordine dei gestori. Nessun dato interno (versioni, autori). */
    @Transactional(readOnly = true)
    public PublicMenu publicMenu() {
        List<MenuStore.CategoryRow> categories = store.categories().stream().filter(MenuStore.CategoryRow::visible).toList();
        List<MenuStore.ItemRow> items = store.items().stream().filter(MenuStore.ItemRow::visible).toList();
        Map<UUID, MediaRef> photos = media.refs(items.stream().map(MenuStore.ItemRow::photoMediaId).toList());
        List<PublicCategory> result = categories.stream()
                .map(c -> new PublicCategory(c.slug(), c.name(), c.hint(), items.stream()
                        .filter(i -> i.categoryId().equals(c.id()))
                        .map(i -> new PublicItem(i.id(), i.name(), i.description(), ingredients(i.ingredients()),
                                i.priceCents(), i.demoPriceCents(), allergens(i), photos.get(i.photoMediaId())))
                        .toList()))
                .toList();
        return new PublicMenu(result, allergens());
    }

    @Transactional
    public CategoryView createCategory(UUID actorId, CategoryCommand command) {
        String name = command.name().strip();
        String slug = slug(command.slug() == null || command.slug().isBlank() ? name : command.slug());
        UUID id = store.insertCategory(slug, name, blankToNull(command.hint()), command.visible());
        audit.record(actorId, AuditAction.MENU_CATEGORY_CREATED, "MENU_CATEGORY", id.toString(), Map.of("slug", slug));
        return category(id);
    }

    @Transactional
    public CategoryView updateCategory(UUID actorId, UUID id, CategoryCommand command) {
        MenuStore.CategoryRow current = store.lockCategory(id).orElseThrow(() -> ApiErrors.notFound("La categoria non esiste più."));
        String slug = command.slug() == null || command.slug().isBlank() ? current.slug() : slug(command.slug());
        store.updateCategory(id, slug, command.name().strip(), blankToNull(command.hint()), command.visible());
        audit.record(actorId, AuditAction.MENU_CATEGORY_UPDATED, "MENU_CATEGORY", id.toString(), Map.of("slug", slug));
        return category(id);
    }

    @Transactional
    public void deleteCategory(UUID actorId, UUID id) {
        MenuStore.CategoryRow current = store.lockCategory(id).orElseThrow(() -> ApiErrors.notFound("La categoria non esiste più."));
        if (current.itemCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_NOT_EMPTY", "Categoria non vuota",
                    "La categoria contiene " + current.itemCount() + " voci: spostale o eliminale prima.",
                    Map.of("items", current.itemCount()));
        }
        store.deleteCategory(id);
        audit.record(actorId, AuditAction.MENU_CATEGORY_DELETED, "MENU_CATEGORY", id.toString(), Map.of("slug", current.slug()));
    }

    @Transactional
    public List<CategoryView> reorderCategories(UUID actorId, List<UUID> ordered) {
        store.reorderCategories(ordered);
        audit.record(actorId, AuditAction.MENU_CATEGORIES_REORDERED, "MENU_CATEGORY", "ordine", Map.of("count", ordered.size()));
        return store.categories().stream().map(MenuService::view).toList();
    }

    @Transactional
    public ItemView createItem(UUID actorId, ItemCommand command) {
        MenuStore.ItemData data = validate(command);
        UUID id = store.insertItem(data, null, actorId, Instant.now(clock));
        audit.record(actorId, AuditAction.MENU_ITEM_CREATED, "MENU_ITEM", id.toString(), summary(data));
        return item(id);
    }

    /** Voce creata dal caricatore demo (profilo local): l'unico punto in cui si scrive un prezzo dimostrativo. */
    @Transactional
    public ItemView createDemoItem(ItemCommand command, Integer demoPriceCents) {
        MenuStore.ItemData data = validate(command);
        UUID id = store.insertItem(data, demoPriceCents, null, Instant.now(clock));
        return item(id);
    }

    @Transactional
    public ItemView updateItem(UUID actorId, UUID id, int version, ItemCommand command) {
        MenuStore.ItemRow current = store.lockItem(id).orElseThrow(MenuService::itemNotFound);
        if (current.version() != version) {
            throw ApiErrors.versionConflict();
        }
        MenuStore.ItemData data = validate(command);
        boolean verified = data.allergensDeclared()
                && (!current.allergensDeclared() || !current.allergenCodes().equals(data.allergenCodes()));
        if (!store.updateItem(id, version, data, verified, actorId, Instant.now(clock))) {
            throw ApiErrors.versionConflict();
        }
        Map<String, Object> changes = summary(data);
        if (!java.util.Objects.equals(current.priceCents(), data.priceCents())) {
            changes.put("previousPriceCents", current.priceCents() == null ? "null" : current.priceCents());
        }
        if (verified) {
            changes.put("allergensVerified", true);
        }
        audit.record(actorId, AuditAction.MENU_ITEM_UPDATED, "MENU_ITEM", id.toString(), changes);
        return item(id);
    }

    @Transactional
    public ItemView setVisibility(UUID actorId, UUID id, int version, boolean visible) {
        MenuStore.ItemRow current = store.lockItem(id).orElseThrow(MenuService::itemNotFound);
        if (current.version() != version || !store.updateVisibility(id, version, visible, Instant.now(clock))) {
            throw ApiErrors.versionConflict();
        }
        audit.record(actorId, AuditAction.MENU_ITEM_UPDATED, "MENU_ITEM", id.toString(), Map.of("visible", visible));
        return item(id);
    }

    @Transactional
    public void deleteItem(UUID actorId, UUID id) {
        MenuStore.ItemRow current = store.lockItem(id).orElseThrow(MenuService::itemNotFound);
        store.deleteItem(id);
        audit.record(actorId, AuditAction.MENU_ITEM_DELETED, "MENU_ITEM", id.toString(), Map.of("name", current.name()));
    }

    private MenuStore.ItemData validate(ItemCommand c) {
        if (c.categoryId() == null || !store.categoryExists(c.categoryId())) {
            throw ApiErrors.invalid("categoryId", "Scegli una categoria esistente.");
        }
        String name = c.name() == null ? "" : c.name().strip();
        if (name.length() < 2 || name.length() > 80) {
            throw ApiErrors.invalid("name", "Dai un nome alla voce (da 2 a 80 caratteri).");
        }
        Integer price = c.priceCents();
        if (price != null && (price < 0 || price > MAX_PRICE_CENTS)) {
            throw ApiErrors.invalid("priceCents", "Il prezzo deve essere tra 0 e 1.000 euro. Lascia vuoto se non è ancora deciso.");
        }
        List<String> ingredients = c.ingredients() == null ? List.of()
                : c.ingredients().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (ingredients.stream().anyMatch(s -> s.contains(",") || s.length() > 60) || ingredients.size() > 20) {
            throw ApiErrors.invalid("ingredients", "Ingredienti: al massimo 20, ognuno di 60 caratteri e senza virgole.");
        }
        String joined = String.join(", ", ingredients);
        if (joined.length() > 400) {
            throw ApiErrors.invalid("ingredients", "L'elenco degli ingredienti è troppo lungo (massimo 400 caratteri).");
        }
        Set<String> codes = c.allergenCodes() == null ? Set.of()
                : c.allergenCodes().stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!c.allergensDeclared() && !codes.isEmpty()) {
            throw ApiErrors.invalid("allergens", "Per indicare gli allergeni conferma prima di averli verificati.");
        }
        Set<String> known = store.allergens().stream().map(MenuStore.Allergen::code).collect(Collectors.toSet());
        List<String> unknown = codes.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw ApiErrors.invalid("allergens", "Allergene non previsto dal Reg. UE 1169/2011: " + String.join(", ", unknown) + ".");
        }
        media.requireReady(c.photoMediaId(), "photoMediaId");
        String description = blankToNull(c.description());
        if (description != null && description.length() > 400) {
            throw ApiErrors.invalid("description", "La descrizione è troppo lunga (massimo 400 caratteri).");
        }
        return new MenuStore.ItemData(c.categoryId(), name, description, joined.isEmpty() ? null : joined, price,
                c.photoMediaId(), c.allergensDeclared(), codes, c.visible());
    }

    private CategoryView category(UUID id) {
        return store.categories().stream().filter(c -> c.id().equals(id)).findFirst().map(MenuService::view).orElseThrow();
    }

    private ItemView item(UUID id) {
        MenuStore.ItemRow row = store.item(id).orElseThrow(MenuService::itemNotFound);
        String slug = store.categories().stream().filter(c -> c.id().equals(row.categoryId())).map(MenuStore.CategoryRow::slug)
                .findFirst().orElse(null);
        return view(row, slug, media.ref(row.photoMediaId()).orElse(null));
    }

    private List<AllergenView> allergens() {
        return store.allergens().stream().map(a -> new AllergenView(a.code(), a.name())).toList();
    }

    private static CategoryView view(MenuStore.CategoryRow c) {
        return new CategoryView(c.id(), c.slug(), c.name(), c.hint(), c.sortOrder(), c.visible(), c.itemCount());
    }

    private static ItemView view(MenuStore.ItemRow i, String slug, MediaRef photo) {
        return new ItemView(i.id(), i.categoryId(), slug, i.name(), i.description(), ingredients(i.ingredients()),
                i.priceCents(), i.demoPriceCents(), allergens(i), photo, i.visible(), i.sortOrder(), i.version(), i.updatedAt());
    }

    private static AllergenInfo allergens(MenuStore.ItemRow i) {
        return new AllergenInfo(i.allergensDeclared(), List.copyOf(i.allergenCodes()), i.allergensVerifiedAt());
    }

    private static List<String> ingredients(String joined) {
        return joined == null || joined.isBlank() ? List.of()
                : Arrays.stream(joined.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static Map<String, Object> summary(MenuStore.ItemData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceCents", d.priceCents() == null ? "null" : d.priceCents());
        m.put("allergensDeclared", d.allergensDeclared());
        m.put("visible", d.visible());
        return m;
    }

    /** «Analcolici & Soft» → {@code analcolici-soft}: identificativo stabile usato nei link {@code /menu?cat=}. */
    static String slug(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            throw ApiErrors.invalid("slug", "Il nome della categoria deve contenere almeno una lettera o un numero.");
        }
        return slug;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException itemNotFound() {
        return ApiErrors.notFound("La voce del menu non esiste più.");
    }
}
