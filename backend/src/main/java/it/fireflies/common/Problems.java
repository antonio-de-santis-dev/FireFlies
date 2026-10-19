package it.fireflies.common;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Costruzione uniforme dei {@code ProblemDetail} (RFC 9457) con le estensioni {@code code} e {@code context}. */
public final class Problems {

    private static final String TYPE_PREFIX = "urn:fireflies:problem:";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(typeFor(code));
        problem.setProperty("code", code);
        return problem;
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail, Map<String, Object> context) {
        ProblemDetail problem = of(status, code, title, detail);
        if (!context.isEmpty()) {
            problem.setProperty("context", context);
        }
        return problem;
    }

    public static URI typeFor(String code) {
        return URI.create(TYPE_PREFIX + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Codice generico per gli errori del framework che non ne hanno uno specifico. */
    public static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "MALFORMED_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "VALIDATION_FAILED";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "HTTP_" + status.value();
        };
    }
}
