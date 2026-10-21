-- FIX-1 (req. 6.3, 6.5, 6.6): capienza unica del locale, niente persone massime dal sito, chiusure e aperture
-- straordinarie anche su un intervallo di giorni. Nessun dato dei clienti toccato: cambiano solo configurazioni.

-- 6.3 — Una sola fonte della capienza: capacity_zones.physical_capacity («Capienza massima del locale»), uguale per
-- ogni giorno e fascia. Spariscono i limiti per giorno/fascia (service_windows.max_covers) e per data
-- (booking_exceptions di tipo CAPACITY_OVERRIDE): potevano solo ABBASSARE la capienza, quindi toglierli non crea mai
-- sovraprenotazioni. Restano le chiusure agli arrivi di una fascia in una data (kind = 'CLOSED').
DELETE FROM booking_exceptions WHERE kind = 'CAPACITY_OVERRIDE';
ALTER TABLE booking_exceptions DROP CONSTRAINT ck_exception_override;
ALTER TABLE booking_exceptions DROP CONSTRAINT ck_exception_kind;
ALTER TABLE booking_exceptions DROP COLUMN capacity_override;
ALTER TABLE booking_exceptions ADD CONSTRAINT ck_exception_kind CHECK (kind = 'CLOSED');

ALTER TABLE service_windows DROP CONSTRAINT ck_window_covers;
ALTER TABLE service_windows DROP COLUMN max_covers;

-- I quanti del registro già creati portano la capienza calcolata con i vecchi limiti: si riallineano alla capienza
-- del locale (mai sotto le persone già confermate: ck_slot_not_overbooked resta l'ultima difesa).
UPDATE capacity_slots s
   SET capacity_covers = z.physical_capacity, updated_at = now()
  FROM capacity_zones z
 WHERE z.id = s.zone_id
   AND s.capacity_covers <> z.physical_capacity
   AND s.occupied_covers <= z.physical_capacity;

-- 6.5 — «Persone massime dal sito» era un secondo limite ridondante: ora il massimo è la capienza del locale
-- (controllo nel backend, insieme al registro dei posti).
ALTER TABLE booking_policies DROP CONSTRAINT ck_policy_party;
ALTER TABLE booking_policies DROP COLUMN max_party_size_web;

-- 6.6 — Chiusure e aperture straordinarie su un giorno o su un intervallo (estremi compresi). Una riga per intervallo:
-- inserimento e modifica atomici, nessun giorno duplicato grazie al vincolo di esclusione (due intervalli non possono
-- sovrapporsi, anche con salvataggi contemporanei).
ALTER TABLE opening_exceptions ADD COLUMN end_day date;
UPDATE opening_exceptions SET end_day = day;
ALTER TABLE opening_exceptions ALTER COLUMN end_day SET NOT NULL;
ALTER TABLE opening_exceptions DROP CONSTRAINT ux_opening_exception_day;
ALTER TABLE opening_exceptions ADD CONSTRAINT ck_opening_exception_range CHECK (end_day >= day AND end_day - day <= 366);
ALTER TABLE opening_exceptions ADD CONSTRAINT ex_opening_exception_overlap
    EXCLUDE USING gist (daterange(day, end_day, '[]') WITH &&);
COMMENT ON COLUMN opening_exceptions.day IS 'Primo giorno della chiusura/apertura straordinaria';
COMMENT ON COLUMN opening_exceptions.end_day IS 'Ultimo giorno compreso (uguale a day per un giorno solo)';
