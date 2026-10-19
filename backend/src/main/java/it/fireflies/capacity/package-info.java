/**
 * Capienza per presenza contemporanea: zone di capienza e registro di occupazione a quanti da 15 minuti
 * (BOOKING_RULES §3, ADR-0006). Unico modulo che scrive {@code capacity_slots}; gli altri usano {@link it.fireflies.capacity.CapacityLedger}.
 * Ordine dei lock: riga evento → riga zona → quanti per zona e orario.
 */
package it.fireflies.capacity;
