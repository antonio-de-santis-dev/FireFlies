package it.fireflies.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** Errori ricorrenti dei comandi dei gestori, con testi uniformi. */
public final class ApiErrors {

    private ApiErrors() {
    }

    /** {@code 422 VALIDATION_FAILED} legato a un campo ({@code context.field}) per evidenziarlo nel modulo. */
    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Dati non validi", message,
                Map.of("field", field));
    }

    /** {@code 409 VERSION_CONFLICT}: qualcuno ha salvato nel frattempo (API_CONTRACT §1, concorrenza ottimistica). */
    public static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Dati cambiati nel frattempo",
                "Qualcun altro ha salvato modifiche nel frattempo: ricarica la pagina e riprova.");
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Non trovato", what);
    }
}
