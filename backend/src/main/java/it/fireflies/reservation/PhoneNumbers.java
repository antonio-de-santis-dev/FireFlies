package it.fireflies.reservation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizzazione dei numeri di telefono in formato E.164 (vincolo {@code ck_res_phone}). Senza prefisso
 * internazionale si assume l'Italia: «333 123 4567» → {@code +393331234567}, «0836 123456» → {@code +390836123456}.
 */
final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

    private PhoneNumbers() {
    }

    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = raw.strip().replaceAll("[\\s.\\-()/]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        } else if (!compact.startsWith("+")) {
            compact = "+39" + compact;
        }
        return E164.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }
}
