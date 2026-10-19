package it.fireflies.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Calcolo delle differenze nette usato da occupazione, rilascio e modifica. */
class CapacityLedgerDeltasTest {

    private static final UUID ZONE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ZONE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant T19 = Instant.parse("2030-05-10T17:00:00Z");

    private static Occupancy occ(UUID zone, Instant start, int minutes, int covers) {
        return new Occupancy(zone, OccupancyInterval.of(start, Duration.ofMinutes(minutes)), covers);
    }

    @Test
    void spostareDiUnOraConservaIQuantiComuni() {
        Occupancy before = occ(ZONE_A, T19, 120, 4);
        Occupancy after = occ(ZONE_A, T19.plus(Duration.ofHours(1)), 120, 4);

        SortedMap<UUID, SortedMap<Instant, Integer>> deltas = CapacityLedger.netDeltas(List.of(before), List.of(after));

        SortedMap<Instant, Integer> zoneA = deltas.get(ZONE_A);
        assertThat(zoneA).hasSize(8); // 4 quanti liberati (19:00–20:00) + 4 occupati (21:00–22:00)
        assertThat(zoneA.get(T19)).isEqualTo(-4);
        assertThat(zoneA.get(T19.plus(Duration.ofHours(2)))).isEqualTo(4);
        assertThat(zoneA).doesNotContainKey(T19.plus(Duration.ofMinutes(60)));
    }

    @Test
    void aumentareLePersoneControllaSoloLaDifferenza() {
        SortedMap<UUID, SortedMap<Instant, Integer>> deltas =
                CapacityLedger.netDeltas(List.of(occ(ZONE_A, T19, 60, 4)), List.of(occ(ZONE_A, T19, 60, 6)));
        assertThat(deltas.get(ZONE_A).values()).hasSize(4).containsOnly(2);
    }

    @Test
    void nessunaDifferenzaNessunAggiornamento() {
        assertThat(CapacityLedger.netDeltas(List.of(occ(ZONE_A, T19, 60, 4)), List.of(occ(ZONE_A, T19, 60, 4)))).isEmpty();
    }

    @Test
    void cambioDiZonaLiberaUnaEOccupaLAltra() {
        SortedMap<UUID, SortedMap<Instant, Integer>> deltas =
                CapacityLedger.netDeltas(List.of(occ(ZONE_B, T19, 60, 3)), List.of(occ(ZONE_A, T19, 60, 3)));
        assertThat(deltas.firstKey()).isEqualTo(ZONE_A); // ordine deterministico dei lock tra zone
        assertThat(deltas.get(ZONE_A).values()).containsOnly(3);
        assertThat(deltas.get(ZONE_B).values()).containsOnly(-3);
    }

    @Test
    void arrotondamentoAlQuantoPrecedente() {
        assertThat(CapacityLedger.floorToQuantum(Instant.parse("2030-05-10T17:14:59Z")))
                .isEqualTo(Instant.parse("2030-05-10T17:00:00Z"));
    }
}
