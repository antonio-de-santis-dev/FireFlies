package it.fireflies.dining;

import java.text.Normalizer;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Fasce del locale per il riepilogo, il calendario di oggi e le statistiche (FASE 7): Colazione, Pomeriggio, Aperitivo,
 * Sera. Una fascia prenotabile con uno di questi nomi appartiene a quella fascia; altrimenti (nomi liberi, prenotazioni
 * per evento, inserimenti fuori fascia) decide l'orario di arrivo.
 */
public enum Band {
    COLAZIONE("Colazione"),
    POMERIGGIO("Pomeriggio"),
    APERITIVO("Aperitivo"),
    SERA("Sera");

    private final String label;

    Band(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Per orario di arrivo: prima delle 11 colazione, fino alle 17 pomeriggio, fino alle 20 aperitivo, poi sera. */
    public static Band at(LocalTime start) {
        if (start.isBefore(LocalTime.of(5, 0))) {
            return SERA; // dopo mezzanotte: coda della sera prima
        }
        if (start.isBefore(LocalTime.of(11, 0))) {
            return COLAZIONE;
        }
        if (start.isBefore(LocalTime.of(17, 0))) {
            return POMERIGGIO;
        }
        return start.isBefore(LocalTime.of(20, 0)) ? APERITIVO : SERA;
    }

    /** Dal nome di una fascia prenotabile (senza accenti né maiuscole), altrimenti dall'orario. */
    public static Band of(String windowName, LocalTime start) {
        if (windowName != null) {
            String n = Normalizer.normalize(windowName, Normalizer.Form.NFD).replaceAll("\\p{M}", "").strip().toUpperCase(Locale.ROOT);
            for (Band b : values()) {
                if (b.name().equals(n)) {
                    return b;
                }
            }
        }
        return at(start);
    }

    public static Band parse(String value) {
        try {
            return value == null || value.isBlank() ? null : valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw it.fireflies.common.ApiErrors.invalid("band", "Fascia sconosciuta: scegli Colazione, Pomeriggio, Aperitivo o Sera.");
        }
    }
}
