package it.fireflies.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.Problems;
import tools.jackson.databind.ObjectMapper;

/**
 * Risposte problem+json per gli errori che nascono nei filtri di sicurezza (prima dei controller):
 * 401 non autenticato o sessione scaduta, 403 permesso negato o CSRF non valido.
 */
class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;
    private final AuditLog audit;

    SecurityProblemHandlers(ObjectMapper json, AuditLog audit) {
        this.json = json;
        this.audit = audit;
    }

    /** Richiesta anonima su una risorsa protetta. Se il browser presentava una sessione non più valida: sessione scaduta. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        if (sessionExpired(request)) {
            write(response, HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Sessione scaduta",
                    "La sessione è scaduta o è stata chiusa. Accedi di nuovo.", request);
        } else {
            write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Accesso richiesto",
                    "Per questa operazione devi accedere all'area gestori.", request);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        if (ex instanceof CsrfException) {
            write(response, HttpStatus.FORBIDDEN, "CSRF_INVALID", "Richiesta non verificata",
                    "Il token di sicurezza della richiesta manca o non è valido. Ricarica la pagina e riprova.", request);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof GestoreDetails gestore) {
            // FIX-1: object_id è varchar(64); i percorsi con un id (es. /api/v1/admin/service-windows/{uuid}) sono più
            // lunghi e il salvataggio dell'audit falliva, trasformando il 403 in un 500. Il percorso intero va nei metadati.
            String uri = request.getRequestURI();
            audit.record(gestore.id(), AuditAction.ACCESS_DENIED, "ENDPOINT", uri.length() <= 64 ? uri : uri.substring(0, 64),
                    Map.of("method", request.getMethod(), "role", gestore.role().name(), "path", uri));
        }
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Permesso negato",
                "Il tuo ruolo non permette questa operazione.", request);
    }

    static boolean sessionExpired(HttpServletRequest request) {
        return request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String title, String detail,
            HttpServletRequest request) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", Problems.typeFor(code).toString());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
