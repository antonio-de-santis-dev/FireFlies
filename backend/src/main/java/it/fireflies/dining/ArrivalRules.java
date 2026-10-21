package it.fireflies.dining;

import java.time.LocalTime;
import java.util.List;

/**
 * Regole interne degli orari di arrivo (FIX 2, branch fasceorarie): l'unica fonte delle fasce prenotabili. Il gestore
 * non configura più le fasce: decide solo giorni e orari di apertura (Impostazioni › Orari, date straordinarie); il
 * sistema propone gli arrivi di queste fasce che cadono interamente dentro l'apertura (arrivo ≥ apertura e arrivo +
 * durata ≤ chiusura).
 *
 * <p>Valori: quelli delle fasce del modulo di prenotazione approvato, gli stessi creati per ogni giorno dai contenuti
 * dimostrativi dalla FASE 7 ({@code DemoContentLoader}, commit 57ad258) e quindi già in uso in tutti i giorni
 * configurati. Nessun orario commerciale nuovo. I nomi coincidono con {@link Band}: la fascia di una prenotazione (budget,
 * duplicati, statistiche) resta quella di sempre ({@code fireflies_band}: nome della fascia prenotabile).
 */
public final class ArrivalRules {

    /** Una fascia: primo e ultimo arrivo, passo tra gli arrivi, durata prevista della permanenza. */
    public record Schedule(Band band, LocalTime firstArrival, LocalTime lastArrival, int stepMinutes, int durationMinutes) {
        public String name() {
            return band.label();
        }
    }

    public static final List<Schedule> STANDARD = List.of(
            new Schedule(Band.COLAZIONE, LocalTime.of(7, 30), LocalTime.of(10, 30), 30, 60),
            new Schedule(Band.POMERIGGIO, LocalTime.of(15, 0), LocalTime.of(17, 0), 60, 60),
            new Schedule(Band.APERITIVO, LocalTime.of(18, 0), LocalTime.of(20, 30), 30, 90),
            new Schedule(Band.SERA, LocalTime.of(21, 0), LocalTime.of(22, 0), 30, 60));

    private ArrivalRules() {
    }
}
