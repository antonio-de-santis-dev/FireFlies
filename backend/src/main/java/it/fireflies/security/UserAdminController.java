package it.fireflies.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/users}: gestione degli account, solo titolare. Doppio controllo: regola sull'URL
 * ({@link SecurityConfiguration}) e {@code @PreAuthorize} sui metodi.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('OWNER')")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class UserAdminController {

    record UserAdminView(UUID id, String email, String displayName, Role role, boolean active,
            Instant lastLoginAt, Instant lockedUntil, int version) {
        static UserAdminView of(AppUser u) {
            return new UserAdminView(u.id(), u.email(), u.displayName(), u.role(), u.active(), u.lastLoginAt(),
                    u.lockedUntil(), u.version());
        }
    }

    record CreateUserRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 2, max = 80) String displayName,
            @NotNull Role role,
            @NotBlank @Size(max = 200) String password) {
    }

    record UpdateUserRequest(@NotNull Integer version, @Size(min = 2, max = 80) String displayName, Role role, Boolean active) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 200) String newPassword) {
    }

    private final UserAccountService accounts;

    UserAdminController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<UserAdminView> list() {
        return accounts.list().stream().map(UserAdminView::of).toList();
    }

    @PostMapping
    ResponseEntity<UserAdminView> create(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody CreateUserRequest body) {
        AppUser created = accounts.create(actor.id(),
                new UserAccountService.NewUser(body.email(), body.displayName(), body.role(), body.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(UserAdminView.of(created));
    }

    @PatchMapping("/{id}")
    UserAdminView update(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest body) {
        return UserAdminView.of(accounts.update(actor.id(), id,
                new UserAccountService.UserChange(body.version(), body.displayName(), body.role(), body.active())));
    }

    @PostMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest body) {
        accounts.resetPassword(actor.id(), id, body.newPassword());
        return ResponseEntity.noContent().build();
    }
}
