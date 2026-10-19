# Evidenza: spike SQL della FASE 1

> Eseguito il 19/10/2026 su un container usa-e-getta `postgres:17-alpine` (PostgreSQL 17.11), poi rimosso. **Non è una migrazione** e non va applicato: serve solo a verificare la semantica dei vincoli proposti in DATA_MODEL.

## Risultati

| Verifica | Esito |
|---|---|
| Conferma condizionale: 8 coperti su 10 poi +4 | 1ª riuscita (8), 2ª **0 righe** (rifiutata) ✅ |
| Abbassare la capienza a 5 con 8 confermati | errore `ck_bucket_not_overbooked` ✅ |
| Doppio invio WEB stesso telefono e orario | errore `ux_res_web_duplicate` ✅; stesso numero da PHONE consentito ✅ |
| Tavolo 7: `[19:30,21:30)` vs `[21:00,23:00)` | errore di esclusione `ex_table_no_overlap` ✅ |
| Tavolo 7: `[19:30,21:30)` vs `[21:30,23:00)` (adiacenti) | consentito ✅ |
| Assegnazione disattivata (`active=false`) libera il tavolo | consentito ✅ |
| Ora inesistente 29/03/2026 02:30 Europe/Rome | PostgreSQL la sposta **in silenzio** alle 03:30 → il controllo va fatto in Java ⚠️ |
| Ora ambigua 25/10/2026 02:30 | PostgreSQL usa +01:00 → in Java fisseremo esplicitamente la regola ⚠️ |
| 20 conferme parallele da 2 su capienza 10 | **5** riuscite, contatore finale **10** ✅ |

## Script

```sql
-- SPIKE USA-E-GETTA (FASE 1): verifica semantica dei vincoli. NON è una migrazione.
\set ON_ERROR_STOP off
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE capacity_buckets (
  id bigserial PRIMARY KEY,
  capacity_covers int NOT NULL CHECK (capacity_covers >= 0),
  confirmed_covers int NOT NULL DEFAULT 0 CHECK (confirmed_covers >= 0),
  CONSTRAINT ck_bucket_not_overbooked CHECK (confirmed_covers <= capacity_covers)
);
CREATE TABLE reservations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  status text NOT NULL,
  source text NOT NULL,
  phone_e164 text NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  guest_count int NOT NULL CHECK (guest_count BETWEEN 1 AND 50),
  CHECK (ends_at > starts_at)
);
CREATE UNIQUE INDEX ux_res_web_dup ON reservations (phone_e164, starts_at)
  WHERE source = 'WEB' AND status IN ('PENDING','CONFIRMED');
CREATE TABLE table_assignments (
  id bigserial PRIMARY KEY,
  reservation_id uuid NOT NULL REFERENCES reservations(id),
  table_id int NOT NULL,
  occupied tstzrange NOT NULL,
  active boolean NOT NULL DEFAULT true,
  CONSTRAINT ex_table_no_overlap EXCLUDE USING gist (table_id WITH =, occupied WITH &&) WHERE (active)
);

\echo '--- 1. capacità: conferma condizionale'
INSERT INTO capacity_buckets (capacity_covers) VALUES (10);
UPDATE capacity_buckets SET confirmed_covers = confirmed_covers + 8 WHERE id = 1 AND confirmed_covers + 8 <= capacity_covers RETURNING confirmed_covers;
UPDATE capacity_buckets SET confirmed_covers = confirmed_covers + 4 WHERE id = 1 AND confirmed_covers + 4 <= capacity_covers RETURNING confirmed_covers; -- atteso: 0 righe
\echo '--- 2. abbassare capienza sotto i confermati: atteso errore CHECK'
UPDATE capacity_buckets SET capacity_covers = 5 WHERE id = 1;

\echo '--- 3. duplicato web: atteso errore unique'
INSERT INTO reservations (status, source, phone_e164, starts_at, ends_at, guest_count) VALUES
 ('PENDING','WEB','+390000000001','2026-10-24 19:30+02','2026-10-24 21:30+02',4);
INSERT INTO reservations (status, source, phone_e164, starts_at, ends_at, guest_count) VALUES
 ('PENDING','WEB','+390000000001','2026-10-24 19:30+02','2026-10-24 21:30+02',4);
\echo '--- 3b. stesso numero da telefono (gestore): consentito'
INSERT INTO reservations (status, source, phone_e164, starts_at, ends_at, guest_count) VALUES
 ('PENDING','PHONE','+390000000001','2026-10-24 19:30+02','2026-10-24 21:30+02',2);

\echo '--- 4. tavolo: sovrapposizione [19:30,21:30) vs [21:00,23:00) -> atteso errore 23P01'
INSERT INTO reservations (id,status, source, phone_e164, starts_at, ends_at, guest_count) VALUES
 ('00000000-0000-0000-0000-00000000000a','CONFIRMED','PHONE','+390000000002','2026-10-24 21:00+02','2026-10-24 23:00+02',2),
 ('00000000-0000-0000-0000-00000000000b','CONFIRMED','PHONE','+390000000003','2026-10-24 21:30+02','2026-10-24 23:00+02',2),
 ('00000000-0000-0000-0000-00000000000c','CONFIRMED','PHONE','+390000000004','2026-10-24 19:30+02','2026-10-24 21:30+02',2);
INSERT INTO table_assignments (reservation_id, table_id, occupied) VALUES ('00000000-0000-0000-0000-00000000000c', 7, tstzrange('2026-10-24 19:30+02','2026-10-24 21:30+02','[)'));
INSERT INTO table_assignments (reservation_id, table_id, occupied) VALUES ('00000000-0000-0000-0000-00000000000a', 7, tstzrange('2026-10-24 21:00+02','2026-10-24 23:00+02','[)'));
\echo '--- 4b. adiacente [21:30,23:00): consentito'
INSERT INTO table_assignments (reservation_id, table_id, occupied) VALUES ('00000000-0000-0000-0000-00000000000b', 7, tstzrange('2026-10-24 21:30+02','2026-10-24 23:00+02','[)'));
\echo '--- 4c. disattivo la prima, ora la sovrapposta passa'
UPDATE table_assignments SET active = false WHERE reservation_id = '00000000-0000-0000-0000-00000000000c';
UPDATE table_assignments SET active = false WHERE reservation_id = '00000000-0000-0000-0000-00000000000b';
INSERT INTO table_assignments (reservation_id, table_id, occupied) VALUES ('00000000-0000-0000-0000-00000000000a', 7, tstzrange('2026-10-24 21:00+02','2026-10-24 23:00+02','[)'));

\echo '--- 5. DST: 2026-10-25 02:30 Europe/Rome (ora ambigua) e 2026-03-29 02:30 (inesistente)'
SELECT ('2026-10-25 02:30'::timestamp AT TIME ZONE 'Europe/Rome') AS ambigua, ('2026-03-29 02:30'::timestamp AT TIME ZONE 'Europe/Rome') AS inesistente;
```

```bash
#!/usr/bin/env bash
# Concorrenza: 20 conferme parallele da 2 coperti su capienza 10 -> esattamente 5 riuscite.
C=pgspike
docker exec $C psql -U postgres -qtAc "TRUNCATE capacity_buckets RESTART IDENTITY; INSERT INTO capacity_buckets (capacity_covers) VALUES (10);"
for i in $(seq 1 20); do
  docker exec $C psql -U postgres -qtAc "UPDATE capacity_buckets SET confirmed_covers = confirmed_covers + 2 WHERE id = 1 AND confirmed_covers + 2 <= capacity_covers RETURNING 'OK';" &
done | grep -c OK
wait
docker exec $C psql -U postgres -qtAc "SELECT 'confirmed_covers=' || confirmed_covers FROM capacity_buckets WHERE id = 1;"
```

## Output completo

```text
CREATE EXTENSION
CREATE TABLE
CREATE TABLE
CREATE INDEX
CREATE TABLE
--- 1. capacità: conferma condizionale
INSERT 0 1
 confirmed_covers 
------------------
                8
(1 row)

UPDATE 1
 confirmed_covers 
------------------
(0 rows)

UPDATE 0
--- 2. abbassare capienza sotto i confermati: atteso errore CHECK
ERROR:  new row for relation "capacity_buckets" violates check constraint "ck_bucket_not_overbooked"
DETAIL:  Failing row contains (1, 5, 8).
--- 3. duplicato web: atteso errore unique
INSERT 0 1
ERROR:  duplicate key value violates unique constraint "ux_res_web_dup"
DETAIL:  Key (phone_e164, starts_at)=(+390000000001, 2026-10-24 19:30:00+02) already exists.
--- 3b. stesso numero da telefono (gestore): consentito
INSERT 0 1
--- 4. tavolo: sovrapposizione [19:30,21:30) vs [21:00,23:00) -> atteso errore 23P01
INSERT 0 3
INSERT 0 1
ERROR:  conflicting key value violates exclusion constraint "ex_table_no_overlap"
DETAIL:  Key (table_id, occupied)=(7, ["2026-10-24 21:00:00+02","2026-10-24 23:00:00+02")) conflicts with existing key (table_id, occupied)=(7, ["2026-10-24 19:30:00+02","2026-10-24 21:30:00+02")).
--- 4b. adiacente [21:30,23:00): consentito
INSERT 0 1
--- 4c. disattivo la prima, ora la sovrapposta passa
UPDATE 1
UPDATE 1
INSERT 0 1
--- 5. DST: 2026-10-25 02:30 Europe/Rome (ora ambigua) e 2026-03-29 02:30 (inesistente)
        ambigua         |      inesistente       
------------------------+------------------------
 2026-10-25 02:30:00+01 | 2026-03-29 03:30:00+02
(1 row)

5
confirmed_covers=10
```
