package it.fireflies.reservation;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import it.fireflies.common.ApiException;
import it.fireflies.dining.Band;

/**
 * Budget di persone confermate per giorno e fascia (FIX 2, BOOKING_RULES §3.7). Ogni fascia operativa di ogni giorno
 * può avere al massimo «capienza del locale» persone confermate in totale, indipendentemente dal ricambio dei tavoli.
 *
 * <p>Il contatore {@code band_capacity_usage} lo aggiorna il database (trigger sulle prenotazioni) nella stessa
 * transazione di ogni cambio di stato o di persone: conferma +persone, annullamento di una confermata −persone,
 * completata e non presentata non restituiscono nulla, un cambio ripetuto non conta due volte. Qui si fa solo il
 * controllo, sotto lock:
 * <ol>
 * <li>zone attive {@code FOR SHARE} in ordine di id: un cambio di capienza ({@code FOR NO KEY UPDATE}) non può
 * avvenire a metà di una conferma, e viceversa;</li>
 * <li>riga del contatore {@code FOR UPDATE}, nell'ordine (giorno, fascia): due conferme sulla stessa fascia si
 * mettono in fila e la seconda rilegge il totale aggiornato;</li>
 * <li>poi il registro dei quanti (capienza contemporanea) come sempre.</li>
 * </ol>
 * Ordine globale dei lock: prenotazione → evento → zone → fasce → quanti.
 */
@Component
public class BandBudget {

    /** Uso di una fascia in un giorno. */
    public record Usage(LocalDate date, Band band, int capacity, int confirmed) {
        public int remaining() {
            return Math.max(0, capacity - confirmed);
        }
    }

    public record Slot(LocalDate date, Band band) {
    }

    private final JdbcClient jdbc;

    BandBudget(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Prima di occupare {@code guests} persone in (giorno, fascia), togliendo eventualmente {@code previous} (stessa
     * prenotazione che cambia giorno, fascia o persone): {@code 409 BAND_CAPACITY_EXCEEDED} se il totale supererebbe la
     * capienza. Non scrive nulla: il contatore lo aggiorna il trigger quando cambia la prenotazione.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void require(LocalDate date, Band band, int guests, Slot previous, int previousGuests) {
        int capacity = lockCapacity();
        List<Slot> slots = previous == null ? List.of(new Slot(date, band))
                : java.util.stream.Stream.of(new Slot(date, band), previous).distinct()
                        .sorted(Comparator.comparing(Slot::date).thenComparing(Slot::band)).toList();
        Map<Slot, Integer> used = new java.util.HashMap<>();
        for (Slot s : slots) {
            used.put(s, lockRow(s));
        }
        int after = used.get(new Slot(date, band)) + guests
                - (previous != null && previous.equals(new Slot(date, band)) ? previousGuests : 0);
        if (after > capacity) {
            int confirmed = used.get(new Slot(date, band));
            throw new ApiException(HttpStatus.CONFLICT, "BAND_CAPACITY_EXCEEDED", "Fascia al completo",
                    "Per " + band.label().toLowerCase(java.util.Locale.ROOT) + " di quel giorno sono già confermate " + confirmed
                            + " persone su " + capacity + ": non ci sono abbastanza posti nella fascia.",
                    Map.of("date", date.toString(), "band", band.name(), "capacity", capacity, "confirmed", confirmed,
                            "remaining", Math.max(0, capacity - confirmed)));
        }
    }

    /**
     * Prima di liberare i quanti di una confermata che si annulla: zone e riga della fascia nello stesso ordine di una
     * conferma (il trigger aggiornerà la riga dopo), così annullamento e conferma contemporanei non vanno in stallo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LocalDate date, Band band) {
        lockCapacity();
        lockRow(new Slot(date, band));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void require(LocalDate date, Band band, int guests) {
        require(date, band, guests, null, 0);
    }

    /** Uso delle fasce in un periodo (sola lettura, nessun lock): riepilogo e anteprima per il sito. */
    @Transactional(readOnly = true)
    public Map<Slot, Integer> usage(LocalDate from, LocalDate to) {
        Map<Slot, Integer> out = new java.util.HashMap<>();
        jdbc.sql("SELECT service_date, band, confirmed_covers FROM band_capacity_usage WHERE service_date BETWEEN ? AND ?")
                .params(from, to)
                .query((rs, n) -> out.put(new Slot(rs.getObject(1, LocalDate.class), Band.valueOf(rs.getString(2))), rs.getInt(3)))
                .list();
        return out;
    }

    /** Uso di una fascia, senza lock. */
    @Transactional(readOnly = true)
    public Usage read(LocalDate date, Band band) {
        Integer confirmed = jdbc.sql("SELECT confirmed_covers FROM band_capacity_usage WHERE service_date = ? AND band = ?")
                .params(date, band.name()).query(Integer.class).optional().orElse(0);
        return new Usage(date, band, capacity(), confirmed);
    }

    public int capacity() {
        return jdbc.sql("SELECT coalesce(sum(physical_capacity), 0) FROM capacity_zones WHERE active").query(Integer.class).single();
    }

    private int lockCapacity() {
        List<Integer> zones = jdbc.sql("SELECT physical_capacity FROM capacity_zones WHERE active ORDER BY id FOR SHARE")
                .query(Integer.class).list();
        return zones.stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    private int lockRow(Slot s) {
        jdbc.sql("INSERT INTO band_capacity_usage (service_date, band) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(s.date(), s.band().name()).update();
        return jdbc.sql("SELECT confirmed_covers FROM band_capacity_usage WHERE service_date = ? AND band = ? FOR UPDATE")
                .params(s.date(), s.band().name()).query(Integer.class).single();
    }
}
