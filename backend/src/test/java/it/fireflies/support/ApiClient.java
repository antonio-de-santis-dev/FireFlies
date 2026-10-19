package it.fireflies.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Client HTTP reale per i test di sicurezza: un «browser» con il suo barattolo di cookie.
 * Gestisce il token CSRF come il frontend: legge il cookie {@code XSRF-TOKEN} e lo rimanda nell'header.
 */
public final class ApiClient {

    public record Response(int status, Map<String, List<String>> headers, String raw, JsonNode body) {

        public String code() {
            return body == null ? null : body.path("code").asString(null);
        }

        public Optional<String> header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst();
        }

        public List<String> setCookies() {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("set-cookie"))
                    .flatMap(e -> e.getValue().stream()).toList();
        }
    }

    private final URI base;
    private final ObjectMapper json;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiClient(int port, ObjectMapper json) {
        this.base = URI.create("http://localhost:" + port);
        this.json = json;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET(), false);
    }

    public Response post(String path, Object body) {
        return send(withBody("POST", path, body), true);
    }

    public Response patch(String path, Object body) {
        return send(withBody("PATCH", path, body), true);
    }

    /** POST senza header CSRF (come farebbe un sito estraneo). */
    public Response postWithoutCsrf(String path, Object body) {
        return send(withBody("POST", path, body), false);
    }

    /** POST con un token CSRF inventato. */
    public Response postWithCsrf(String path, Object body, String token) {
        return send(withBody("POST", path, body).header("X-XSRF-TOKEN", token), false);
    }

    public Response login(String email, String password) {
        return post("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue).findFirst();
    }

    /** Imposta un cookie a mano (es. un id di sessione scelto da un attaccante). */
    public void setCookie(String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath("/");
        cookie.setVersion(0);
        cookies.getCookieStore().add(base, cookie);
    }

    public void clearCookie(String name) {
        cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).toList()
                .forEach(c -> cookies.getCookieStore().remove(base, c));
    }

    private HttpRequest.Builder withBody(String method, String path, Object body) {
        return HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
    }

    private Response send(HttpRequest.Builder request, boolean withCsrf) {
        if (withCsrf) {
            if (cookie("XSRF-TOKEN").isEmpty()) {
                get("/api/v1/auth/csrf");
            }
            cookie("XSRF-TOKEN").ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        }
        request.header("Accept", "application/json, application/problem+json");
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode body = raw == null || raw.isBlank() ? null : json.readTree(raw);
            return new Response(response.statusCode(), response.headers().map(), raw, body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
