package it.fireflies.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Errore applicativo con codice stabile per il frontend, tradotto in {@code application/problem+json}
 * da {@link GlobalExceptionHandler}. Il messaggio è in italiano e pronto per la UI.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, Object> context;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, Map.of());
    }

    public ApiException(HttpStatus status, String code, String title, String detail, Map<String, Object> context) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.context = Map.copyOf(context);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> context() {
        return context;
    }
}
