-- Orari riservati oggi a eventi esclusivi (futuri), per ricontrollarli dalle API pubbliche dopo il reset.
-- Colonne: data | prima ora | ultima ora (Europe/Rome). Nessun dato personale.
\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'
SELECT (slot_start AT TIME ZONE 'Europe/Rome')::date,
       to_char(min(slot_start) AT TIME ZONE 'Europe/Rome', 'HH24:MI'),
       to_char(max(slot_start) AT TIME ZONE 'Europe/Rome', 'HH24:MI')
  FROM capacity_slots
 WHERE exclusive_event_id IS NOT NULL AND slot_start > now()
 GROUP BY 1 ORDER BY 1;
