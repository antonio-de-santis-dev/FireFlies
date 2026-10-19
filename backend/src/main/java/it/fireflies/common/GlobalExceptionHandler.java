package it.fireflies.common;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Tutti gli errori dell'API escono come {@code application/problem+json} con un {@code code} stabile
 * (API_CONTRACT §1). Nessuno stack trace, nome di vincolo o dettaglio interno raggiunge il client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    record FieldProblem(String field, String code, String message) {
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.status(), ex.code(), ex.title(), ex.getMessage(), ex.context());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.context().get("retryAfterSeconds") instanceof Number seconds) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        ConstraintViolationTranslator.Translation t = ConstraintViolationTranslator.translate(ex);
        log.info("Vincolo del database violato: {} → {}",
                ConstraintViolationTranslator.constraintName(ex).orElse("sconosciuto"), t.code());
        return Problems.of(t.status(), t.code(), t.title(), t.detail());
    }

    /**
     * Deadlock, timeout di lock o serializzazione: l'operazione è stata annullata per intero e si può ripetere.
     * Con i lock ordinati non dovrebbe accadere; resta come rete di sicurezza.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail handleLockFailure(PessimisticLockingFailureException ex) {
        log.warn("Operazione annullata per conflitto di lock: {}", ex.getClass().getSimpleName());
        return Problems.of(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Operazione in conflitto",
                "Un'altra operazione stava modificando gli stessi dati. Nulla è stato salvato: riprova.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleBeanValidation(ConstraintViolationException ex) {
        List<FieldProblem> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(v.getPropertyPath().toString(), "INVALID", v.getMessage()))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "Dati non validi", "Alcuni campi non sono validi.");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Errore non gestito", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Errore imprevisto", "Si è verificato un errore imprevisto. Riprova tra poco.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldProblem(e.getField(),
                        e.getCode() == null ? "INVALID" : e.getCode().toUpperCase(),
                        e.getDefaultMessage() == null ? "Valore non valido." : e.getDefaultMessage()))
                .toList();
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "Dati non validi", "Alcuni campi non sono validi.");
        problem.setProperty("errors", errors);
        return ResponseEntity.unprocessableContent().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Richiesta non leggibile", "Il corpo della richiesta non è un JSON valido per questa operazione.");
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /** Upload oltre il limite di Spring ({@code spring.servlet.multipart.*}): stesso messaggio del controllo applicativo. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.CONTENT_TOO_LARGE, "MEDIA_TOO_LARGE", "Foto troppo pesante",
                "La foto pesa più di 5 MB: riducila (o esportala in JPG) e riprova.");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers).body(problem);
    }

    /** Gli errori standard di Spring MVC ricevono comunque {@code code} e {@code type} coerenti. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            String code = Problems.defaultCode(statusCode);
            problem.setProperty("code", code);
            problem.setType(Problems.typeFor(code));
            if (statusCode.value() == 404) {
                problem.setTitle("Non trovato");
                problem.setDetail("La risorsa richiesta non esiste.");
            }
        }
        return response;
    }
}
