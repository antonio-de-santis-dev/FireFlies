# Evidenza: spike SQL 2 — revisione FASE 1

> Eseguito il 19/10/2026 su un container usa-e-getta `postgres:17-alpine` (PostgreSQL 17.11), poi rimosso. **Non è una migrazione**: le funzioni PL/pgSQL servono solo a simulare in SQL le transazioni che in FASE 2 saranno scritte in Java (stessi passi: crea quanti mancanti → lock ordinato → controllo → aggiornamento).

## Risultati

| Verifica | Esito |
|---|---|
| A. 60 conferme **parallele** con orari e durate casuali (arrivi 18:00–21:00, 60–180 min, 1–6 persone) su zona da 10 | 5 riuscite, 55 rifiutate `CAPACITY_EXCEEDED`; **0 deadlock**, 0 errori |
| A. Massima presenza contemporanea ricalcolata dalle prenotazioni confermate | **10** (= capienza, mai superata) ✅ |
| A. Quanti in cui il registro differisce dal ricalcolo | **0** ✅ |
| A. Caso guida: 19:00–21:00 ×6, poi 20:00–22:00 ×6 (sovrapposizione 12 > 10), poi 21:00–23:00 ×6 | OK · **rifiutata** · OK ✅ — il vecchio modello «fascia di arrivo» le avrebbe accettate tutte se in fasce diverse |
| B. 20 gare «pubblica evento esclusivo» vs «conferma ordinaria» negli stessi orari | in ogni gara vince esattamente una delle due (7 eventi, 13 ordinarie); **0** quanti con evento esclusivo e coperti ordinari ✅ |
| C. 4 worker outbox in parallelo su 200 messaggi (`FOR UPDATE SKIP LOCKED`) | 200 presi, **0 presi due volte** ✅ (garanzia sulla *presa in carico*, non sulla consegna) |
| D. Worker con lease scaduto prova a registrare l'esito dopo che un altro ha ripreso il messaggio | aggiornamento con il token vecchio: **0 righe**; il nuovo worker registra regolarmente ✅ |

## Script

```sql
-- SPIKE 2 USA-E-GETTA (revisione FASE 1). NON è una migrazione.
-- Verifica: registro di occupazione a quanti da 15 minuti, eventi esclusivi, claim dell'outbox.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE capacity_slots (
  zone_id int NOT NULL,
  slot_start timestamptz NOT NULL,
  capacity_covers int NOT NULL CHECK (capacity_covers >= 0),
  occupied_covers int NOT NULL DEFAULT 0 CHECK (occupied_covers >= 0),
  exclusive_event_id int NULL,
  PRIMARY KEY (zone_id, slot_start),
  CONSTRAINT ck_slot_aligned CHECK (extract(epoch FROM slot_start)::bigint % 900 = 0),
  CONSTRAINT ck_slot_not_overbooked CHECK (occupied_covers <= capacity_covers)
);
CREATE TABLE reservations (
  id serial PRIMARY KEY, zone_id int NOT NULL, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
  guests int NOT NULL, status text NOT NULL,
  CHECK (extract(epoch FROM starts_at)::bigint % 900 = 0 AND extract(epoch FROM ends_at)::bigint % 900 = 0)
);

-- Conferma di una prenotazione ordinaria: un'unica transazione (una chiamata di funzione).
CREATE FUNCTION try_confirm(z int, s timestamptz, e timestamptz, n int) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers)
    SELECT z, g, 10 FROM generate_series(s, e - interval '15 minutes', interval '15 minutes') g
    ON CONFLICT DO NOTHING;
  -- lock in ordine deterministico: niente deadlock tra intervalli sovrapposti
  PERFORM 1 FROM capacity_slots WHERE zone_id = z AND slot_start >= s AND slot_start < e
    ORDER BY slot_start FOR UPDATE;
  IF EXISTS (SELECT 1 FROM capacity_slots WHERE zone_id = z AND slot_start >= s AND slot_start < e
             AND exclusive_event_id IS NOT NULL) THEN RETURN 'EVENT_BLOCKS_SLOT'; END IF;
  IF EXISTS (SELECT 1 FROM capacity_slots WHERE zone_id = z AND slot_start >= s AND slot_start < e
             AND occupied_covers + n > capacity_covers) THEN RETURN 'CAPACITY_EXCEEDED'; END IF;
  UPDATE capacity_slots SET occupied_covers = occupied_covers + n
    WHERE zone_id = z AND slot_start >= s AND slot_start < e;
  INSERT INTO reservations (zone_id, starts_at, ends_at, guests, status) VALUES (z, s, e, n, 'CONFIRMED');
  RETURN 'OK';
END $$;

-- Pubblicazione di un evento esclusivo: blocca i quanti solo se liberi.
CREATE FUNCTION try_publish_exclusive(z int, s timestamptz, e timestamptz, ev int) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers)
    SELECT z, g, 10 FROM generate_series(s, e - interval '15 minutes', interval '15 minutes') g
    ON CONFLICT DO NOTHING;
  PERFORM 1 FROM capacity_slots WHERE zone_id = z AND slot_start >= s AND slot_start < e
    ORDER BY slot_start FOR UPDATE;
  IF EXISTS (SELECT 1 FROM capacity_slots WHERE zone_id = z AND slot_start >= s AND slot_start < e
             AND (occupied_covers > 0 OR exclusive_event_id IS NOT NULL)) THEN RETURN 'EVENT_CONFLICTS'; END IF;
  UPDATE capacity_slots SET exclusive_event_id = ev WHERE zone_id = z AND slot_start >= s AND slot_start < e;
  RETURN 'OK';
END $$;

-- Outbox
CREATE TABLE outbox (id int PRIMARY KEY, state text NOT NULL, lease_token uuid, locked_until timestamptz);
CREATE TABLE claims (outbox_id int, worker int, token uuid);
INSERT INTO outbox SELECT g, 'PENDING' FROM generate_series(1, 200) g;
```

```bash
#!/usr/bin/env bash
# Spike 2: gare reali tra transazioni su PostgreSQL 17 (container usa-e-getta).
C=pgspike2
q() { docker exec $C psql -U postgres -qtAX -c "$1"; }
DIR=$(dirname "$0")

docker exec -i $C psql -U postgres -q < "$DIR/schema.sql"

echo "=== A. Intervalli sovrapposti: 60 conferme parallele, zona capienza 10, arrivi 18:00-21:00, durate 60-180 min"
RANDOM=42
for i in $(seq 1 60); do
  k=$((RANDOM % 13)); d=$(( (RANDOM % 9 + 4) * 15 )); n=$((RANDOM % 6 + 1))
  q "SELECT try_confirm(1, '2026-10-24 18:00+02'::timestamptz + interval '$((k*15)) minutes',
       '2026-10-24 18:00+02'::timestamptz + interval '$((k*15+d)) minutes', $n);" &
done > "$DIR/a.out" 2> "$DIR/a.err"
wait
sort "$DIR/a.out" | uniq -c
echo "deadlock/errori: $(grep -ci deadlock "$DIR/a.err") / $(grep -c ERROR "$DIR/a.err")"
echo "-- massima presenza ricalcolata dalle prenotazioni confermate (deve essere <= 10):"
q "SELECT max(p) FROM (SELECT g, sum(r.guests) p FROM generate_series('2026-10-24 18:00+02'::timestamptz, '2026-10-25 00:00+02', interval '15 minutes') g
   JOIN reservations r ON r.status='CONFIRMED' AND r.starts_at <= g AND r.ends_at > g GROUP BY g) x;"
echo "-- quanti in cui registro != ricalcolo (deve essere 0):"
q "SELECT count(*) FROM capacity_slots s WHERE s.occupied_covers <> coalesce((SELECT sum(guests) FROM reservations r
   WHERE r.zone_id = s.zone_id AND r.status='CONFIRMED' AND r.starts_at <= s.slot_start AND r.ends_at > s.slot_start),0);"
echo "-- esempio: 19:00-21:00 x6 e 20:00-22:00 x6 sulla zona 2 (sovrapposizione 20:00-21:00 = 12 > 10)"
q "SELECT try_confirm(2, '2026-10-24 19:00+02', '2026-10-24 21:00+02', 6);"
q "SELECT try_confirm(2, '2026-10-24 20:00+02', '2026-10-24 22:00+02', 6);"
q "SELECT try_confirm(2, '2026-10-24 21:00+02', '2026-10-24 23:00+02', 6);"

echo "=== B. Evento esclusivo vs conferme ordinarie in parallelo (zona 3), ripetuto 20 volte"
for r in $(seq 1 20); do
  base="2026-11-$(printf %02d $r) 21:00+01"
  ( q "SELECT 'EV:' || try_publish_exclusive(3, '$base', '$base'::timestamptz + interval '3 hours', $r);" ) &
  ( q "SELECT 'ORD:' || try_confirm(3, '$base'::timestamptz + interval '1 hour', '$base'::timestamptz + interval '2 hours', 4);" ) &
  wait
done > "$DIR/b.out" 2>&1
sort "$DIR/b.out" | uniq -c
echo "-- quanti con evento esclusivo E coperti ordinari (deve essere 0):"
q "SELECT count(*) FROM capacity_slots WHERE zone_id=3 AND exclusive_event_id IS NOT NULL AND occupied_covers > 0;"

echo "=== C. Outbox: 4 worker in parallelo su 200 messaggi con FOR UPDATE SKIP LOCKED"
for w in 1 2 3 4; do
  ( for loop in $(seq 1 15); do
      q "WITH c AS (UPDATE outbox SET state='SENDING', lease_token=gen_random_uuid(), locked_until=now()+interval '1 minute'
           WHERE id IN (SELECT id FROM outbox WHERE state='PENDING' ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED)
           RETURNING id, lease_token)
         INSERT INTO claims SELECT id, $w, lease_token FROM c;"
    done ) &
done
wait
q "SELECT 'messaggi presi: ' || count(DISTINCT outbox_id) || ', prese totali: ' || count(*) || ', presi due volte: ' ||
   (SELECT count(*) FROM (SELECT outbox_id FROM claims GROUP BY 1 HAVING count(*) > 1) d) FROM claims;"

echo "=== D. Lease scaduto: il worker lento non può sovrascrivere l'esito del worker che ha ripreso il messaggio"
q "UPDATE outbox SET state='SENDING', lease_token='00000000-0000-0000-0000-00000000000a', locked_until=now()-interval '1 second' WHERE id=1;"
q "UPDATE outbox SET lease_token='00000000-0000-0000-0000-00000000000b', locked_until=now()+interval '1 minute'
   WHERE id=1 AND state='SENDING' AND locked_until < now() RETURNING 'ripreso dal worker B';"
q "UPDATE outbox SET state='SENT' WHERE id=1 AND lease_token='00000000-0000-0000-0000-00000000000a' RETURNING 'A ha scritto (ERRORE)';" ; echo "worker A con token vecchio: righe aggiornate = 0 se nessun output sopra"
q "UPDATE outbox SET state='SENT' WHERE id=1 AND lease_token='00000000-0000-0000-0000-00000000000b' RETURNING 'B ha registrato SENT';"
```

## Output completo

```text
=== A. Intervalli sovrapposti: 60 conferme parallele, zona capienza 10, arrivi 18:00-21:00, durate 60-180 min
     55 CAPACITY_EXCEEDED
      5 OK
deadlock/errori: 0 / 0
-- massima presenza ricalcolata dalle prenotazioni confermate (deve essere <= 10):
10
-- quanti in cui registro != ricalcolo (deve essere 0):
0
-- esempio: 19:00-21:00 x6 e 20:00-22:00 x6 sulla zona 2 (sovrapposizione 20:00-21:00 = 12 > 10)
OK
CAPACITY_EXCEEDED
OK
=== B. Evento esclusivo vs conferme ordinarie in parallelo (zona 3), ripetuto 20 volte
     13 EV:EVENT_CONFLICTS
      7 EV:OK
      7 ORD:EVENT_BLOCKS_SLOT
     13 ORD:OK
-- quanti con evento esclusivo E coperti ordinari (deve essere 0):
0
=== C. Outbox: 4 worker in parallelo su 200 messaggi con FOR UPDATE SKIP LOCKED
messaggi presi: 200, prese totali: 200, presi due volte: 0
=== D. Lease scaduto: il worker lento non può sovrascrivere l'esito del worker che ha ripreso il messaggio
ripreso dal worker B
worker A con token vecchio: righe aggiornate = 0 se nessun output sopra
B ha registrato SENT
```
