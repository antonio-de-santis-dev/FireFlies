package it.fireflies.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;

import it.fireflies.common.ApiException;

/**
 * Regole per le nuove password dei gestori. Lunghezza minima 12 caratteri (consigliata una frase);
 * massimo 72 byte perché bcrypt ignora i byte successivi.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void check(String password, String email) {
        String problem = problem(password, email);
        if (problem != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WEAK_PASSWORD", "Password non adatta", problem,
                    Map.of("minLength", MIN_LENGTH));
        }
    }

    static String problem(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return "La password deve avere almeno " + MIN_LENGTH + " caratteri: va bene anche una frase.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "La password è troppo lunga (massimo " + MAX_BYTES + " byte).";
        }
        if (password.isBlank() || password.chars().distinct().count() < 4) {
            return "La password è troppo ripetitiva.";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String local = email == null ? "" : UserStore.normalizeEmail(email).split("@")[0];
        if (local.length() >= 4 && lower.contains(local)) {
            return "La password non deve contenere il nome dell'email.";
        }
        return null;
    }
}
