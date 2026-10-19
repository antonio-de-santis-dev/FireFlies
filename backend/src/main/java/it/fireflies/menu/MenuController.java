package it.fireflies.menu;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import it.fireflies.security.GestoreDetails;

/** Menu pubblico ({@code GET /api/v1/public/menu}) e gestione {@code /api/v1/admin/menu/**} (staff e titolare). */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MenuController {

    record CategoryRequest(@Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Solo lettere minuscole, numeri e trattini.")
            @Size(max = 40) String slug,
            @NotBlank @Size(max = 40) String name,
            @Size(max = 80) String hint,
            @NotNull Boolean visible) {

        MenuService.CategoryCommand command() {
            return new MenuService.CategoryCommand(slug, name, hint, visible);
        }
    }

    record AllergensRequest(@NotNull Boolean declared, @NotNull @Size(max = 14) Set<@NotBlank String> codes) {
    }

    /** {@code priceCents}: intero in centesimi ({@code 650} = 6,50 €), {@code null} = «Prezzo da definire». */
    record ItemRequest(Integer version,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 400) String description,
            @Size(max = 20) List<@NotNull @Size(max = 60) String> ingredients,
            Integer priceCents,
            @NotNull @Valid AllergensRequest allergens,
            UUID photoMediaId,
            @NotNull Boolean visible) {

        MenuService.ItemCommand command() {
            return new MenuService.ItemCommand(categoryId, name, description, ingredients, priceCents,
                    allergens.declared(), allergens.codes(), photoMediaId, visible);
        }
    }

    record VisibilityRequest(@NotNull Integer version, @NotNull Boolean visible) {
    }

    record CategoryOrder(@NotNull @Size(max = 200) List<@NotNull UUID> categoryIds) {
    }

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    @GetMapping("/api/v1/public/menu")
    ResponseEntity<MenuService.PublicMenu> publicMenu() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(menu.publicMenu());
    }

    @GetMapping("/api/v1/admin/menu")
    @PreAuthorize("hasRole('STAFF')")
    MenuService.AdminMenu adminMenu() {
        return menu.adminMenu();
    }

    @PostMapping("/api/v1/admin/menu/categories")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<MenuService.CategoryView> createCategory(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody CategoryRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menu.createCategory(actor.id(), body.command()));
    }

    @PutMapping("/api/v1/admin/menu/categories/order")
    @PreAuthorize("hasRole('STAFF')")
    List<MenuService.CategoryView> reorderCategories(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody CategoryOrder body) {
        return menu.reorderCategories(actor.id(), body.categoryIds());
    }

    @PutMapping("/api/v1/admin/menu/categories/{id}")
    @PreAuthorize("hasRole('STAFF')")
    MenuService.CategoryView updateCategory(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest body) {
        return menu.updateCategory(actor.id(), id, body.command());
    }

    @DeleteMapping("/api/v1/admin/menu/categories/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> deleteCategory(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        menu.deleteCategory(actor.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/menu/items")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<MenuService.ItemView> createItem(@AuthenticationPrincipal GestoreDetails actor,
            @Valid @RequestBody ItemRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menu.createItem(actor.id(), body.command()));
    }

    @PutMapping("/api/v1/admin/menu/items/{id}")
    @PreAuthorize("hasRole('STAFF')")
    MenuService.ItemView updateItem(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody ItemRequest body) {
        if (body.version() == null) {
            throw it.fireflies.common.ApiErrors.invalid("version", "Manca la versione letta della voce: ricarica la pagina.");
        }
        return menu.updateItem(actor.id(), id, body.version(), body.command());
    }

    @PatchMapping("/api/v1/admin/menu/items/{id}/visibility")
    @PreAuthorize("hasRole('STAFF')")
    MenuService.ItemView visibility(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody VisibilityRequest body) {
        return menu.setVisibility(actor.id(), id, body.version(), body.visible());
    }

    @DeleteMapping("/api/v1/admin/menu/items/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> deleteItem(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        menu.deleteItem(actor.id(), id);
        return ResponseEntity.noContent().build();
    }
}
