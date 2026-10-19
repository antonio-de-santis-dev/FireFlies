package it.fireflies.security;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/auth}: token CSRF, login, stato della sessione, utente corrente, cambio password. Il logout è nei filtri. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController {

    /** Solo email e password: campi in più (es. un ruolo) fanno rifiutare la richiesta con {@code 400}. */
    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {
    }

    record UserView(UUID id, String email, String displayName, Role role) {
        static UserView of(GestoreDetails g) {
            return new UserView(g.id(), g.email(), g.displayName(), g.role());
        }
    }

    record LoginResponse(UserView user) {
    }

    /** {@code reason}: {@code SESSION_EXPIRED} se il browser presentava una sessione scaduta o chiusa. */
    record SessionView(boolean authenticated, UserView user, String reason) {
    }

    record CsrfView(String headerName, String cookieName) {
    }

    record PasswordChangeRequest(@NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
    }

    private final LoginService loginService;
    private final UserAccountService accounts;

    AuthController(LoginService loginService, UserAccountService accounts) {
        this.loginService = loginService;
        this.accounts = accounts;
    }

    /** Genera il cookie {@code XSRF-TOKEN}: il frontend lo rimanda nell'header {@code X-XSRF-TOKEN}. */
    @GetMapping("/csrf")
    ResponseEntity<CsrfView> csrf(CsrfToken token) {
        token.getToken(); // forza la creazione del cookie
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CsrfView(token.getHeaderName(), "XSRF-TOKEN"));
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        return new LoginResponse(UserView.of(loginService.login(body.email(), body.password(), request, response)));
    }

    /** Sempre {@code 200}: il frontend sa se mostrare il login senza generare errori nella console. */
    @GetMapping("/session")
    SessionView session(Authentication authentication, HttpServletRequest request) {
        GestoreDetails gestore = SecurityConfiguration.gestore(authentication);
        if (gestore != null) {
            return new SessionView(true, UserView.of(gestore), null);
        }
        return new SessionView(false, null, SecurityProblemHandlers.sessionExpired(request) ? "SESSION_EXPIRED" : null);
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal GestoreDetails gestore) {
        return UserView.of(gestore);
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal GestoreDetails gestore,
            @Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request) {
        accounts.changeOwnPassword(gestore.id(), body.currentPassword(), body.newPassword(), request.getSession().getId());
        return ResponseEntity.noContent().build();
    }
}
