package it.fireflies.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import it.fireflies.support.ApiClient;
import it.fireflies.reservation.ReservationTestSupport;

/**
 * FASE 8 — Matrice dei permessi ricavata da <b>tutti</b> gli endpoint registrati (nessuno dimenticato): ogni API dei
 * gestori senza sessione → 401; ogni scrittura senza token CSRF → 403; le API del solo titolare con lo staff → 403;
 * nessun endpoint fuori da {@code /api/v1/public}, {@code /api/v1/auth} e {@code /api/v1/admin}.
 */
class ApiSecurityMatrixIT extends ReservationTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private record Endpoint(String method, String path) {
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            Set<String> patterns = info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    out.add(new Endpoint(m.name(), p.replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000000")));
                }
            }
        });
        return out;
    }

    private ApiClient.Response call(ApiClient c, Endpoint e, boolean csrf) {
        return switch (e.method()) {
            case "GET" -> c.get(e.path());
            case "POST" -> csrf ? c.post(e.path(), Map.of()) : c.postWithoutCsrf(e.path(), Map.of());
            case "PUT" -> c.put(e.path(), Map.of());
            case "PATCH" -> c.patch(e.path(), Map.of());
            case "DELETE" -> c.delete(e.path());
            default -> throw new IllegalStateException(e.method());
        };
    }

    @Test
    void tuttiGliEndpointSottoIPrefissiPrevisti() {
        assertThat(endpoints()).isNotEmpty().allSatisfy(e -> assertThat(e.path())
                .matches("^/(api/v1/(public|auth|admin)/.*|error|media/.*)$"));
    }

    @Test
    void apiDeiGestoriSenzaSessione401() {
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (!e.path().startsWith("/api/v1/admin/")) {
                continue;
            }
            ApiClient anonymous = browser();
            int status = call(anonymous, e, true).status();
            if (status != 401) {
                wrong.add(e + " → " + status);
            }
        }
        assertThat(wrong).as("endpoint dei gestori raggiungibili senza accesso").isEmpty();
    }

    @Test
    void scrittureSenzaCsrf403() {
        ApiClient owner = signedIn(Role.OWNER);
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            if (!e.method().equals("POST") || e.path().equals("/api/v1/auth/login")) {
                continue;
            }
            int status = owner.postWithoutCsrf(e.path(), Map.of()).status();
            if (status != 403) {
                wrong.add(e + " → " + status);
            }
        }
        assertThat(wrong).as("scritture accettate senza token CSRF").isEmpty();
    }

    @Test
    void apiDelTitolareRifiutateAlloStaff() {
        ApiClient staff = signedIn(Role.STAFF);
        List<Endpoint> ownerOnly = new ArrayList<>(List.of(
                new Endpoint("GET", "/api/v1/admin/users"), new Endpoint("POST", "/api/v1/admin/users"),
                new Endpoint("GET", "/api/v1/admin/site-settings"), new Endpoint("PUT", "/api/v1/admin/site-settings"),
                new Endpoint("GET", "/api/v1/admin/site-content"), new Endpoint("PUT", "/api/v1/admin/site-content"),
                new Endpoint("PUT", "/api/v1/admin/venue-capacity"), new Endpoint("POST", "/api/v1/admin/opening-exceptions"),
                new Endpoint("PUT", "/api/v1/admin/booking-policy"), new Endpoint("POST", "/api/v1/admin/booking-exceptions"),
                new Endpoint("POST", "/api/v1/admin/service-windows"), new Endpoint("POST", "/api/v1/admin/capacity-zones"),
                // FIX-1: percorsi con un id oltre i 64 caratteri davano 500 invece di 403 (audit troppo lungo)
                new Endpoint("PUT", "/api/v1/admin/service-windows/00000000-0000-0000-0000-000000000000"),
                new Endpoint("PUT", "/api/v1/admin/opening-exceptions/00000000-0000-0000-0000-000000000000"),
                new Endpoint("DELETE", "/api/v1/admin/opening-exceptions/00000000-0000-0000-0000-000000000000"),
                new Endpoint("DELETE", "/api/v1/admin/booking-exceptions/00000000-0000-0000-0000-000000000000"),
                // FIX 3 (B-01): la lettura delle chiusure straordinarie dava 500 allo staff invece di 403
                new Endpoint("GET", "/api/v1/admin/opening-exceptions")));
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : ownerOnly) {
            int status = call(staff, e, true).status();
            if (status != 403) {
                wrong.add(e + " → " + status);
            }
        }
        assertThat(wrong).as("API del titolare permesse allo staff").isEmpty();
    }
}
