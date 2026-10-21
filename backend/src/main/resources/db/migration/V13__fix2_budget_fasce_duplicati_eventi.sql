-- FIX 2: budget di persone confermate per giorno e fascia, duplicati per telefono/giorno/fascia, eventi al minuto.
-- Migrazione incrementale e non distruttiva: nessuna prenotazione, evento o dato del cliente viene cancellato o
-- modificato nei suoi orari; si aggiungono una colonna derivata, un contatore e vincoli.

-- 1. Fascia di ogni prenotazione (Colazione, Pomeriggio, Aperitivo, Sera), con la stessa regola di dining/Band.of:
--    il nome della fascia prenotabile se è uno dei quattro, altrimenti l'orario di arrivo (Europe/Rome):
--    prima delle 05 sera (coda della sera), prima delle 11 colazione, prima delle 17 pomeriggio, prima delle 20 aperitivo.
CREATE FUNCTION fireflies_band(p_start timestamptz, p_window uuid) RETURNS text
    LANGUAGE sql STABLE AS $$
    SELECT COALESCE(
        (SELECT CASE upper(btrim(w.name))
                    WHEN 'COLAZIONE' THEN 'COLAZIONE' WHEN 'POMERIGGIO' THEN 'POMERIGGIO'
                    WHEN 'APERITIVO' THEN 'APERITIVO' WHEN 'SERA' THEN 'SERA' END
           FROM service_windows w WHERE w.id = p_window),
        CASE WHEN (p_start AT TIME ZONE 'Europe/Rome')::time < time '05:00' THEN 'SERA'
             WHEN (p_start AT TIME ZONE 'Europe/Rome')::time < time '11:00' THEN 'COLAZIONE'
             WHEN (p_start AT TIME ZONE 'Europe/Rome')::time < time '17:00' THEN 'POMERIGGIO'
             WHEN (p_start AT TIME ZONE 'Europe/Rome')::time < time '20:00' THEN 'APERITIVO'
             ELSE 'SERA' END)
$$;

ALTER TABLE reservations ADD COLUMN service_band text;
UPDATE reservations SET service_band = fireflies_band(starts_at, service_window_id);
ALTER TABLE reservations ALTER COLUMN service_band SET NOT NULL;
ALTER TABLE reservations ADD CONSTRAINT ck_res_band CHECK (service_band IN ('COLAZIONE', 'POMERIGGIO', 'APERITIVO', 'SERA'));

-- la fascia segue sempre orario e fascia prenotabile (anche per gli spostamenti delle richieste di un evento)
CREATE FUNCTION fireflies_res_band() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.service_band := fireflies_band(NEW.starts_at, NEW.service_window_id);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_res_band BEFORE INSERT OR UPDATE OF starts_at, service_window_id ON reservations
    FOR EACH ROW EXECUTE FUNCTION fireflies_res_band();

-- 2. Budget per giorno e fascia: persone CONFIRMED + COMPLETED + NO_SHOW (completare o non presentarsi non restituisce
--    il budget; annullare una confermata sì). Il contatore lo aggiorna il trigger qui sotto, nella stessa transazione
--    di ogni cambio: un cambio di stato ripetuto non può contare due volte. Il controllo «non oltre la capienza» lo fa il
--    backend sotto lock della riga (la capienza è un'impostazione e può cambiare).
CREATE TABLE band_capacity_usage (
    service_date     date        NOT NULL,
    band             text        NOT NULL,
    confirmed_covers integer     NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_date, band),
    CONSTRAINT ck_usage_band CHECK (band IN ('COLAZIONE', 'POMERIGGIO', 'APERITIVO', 'SERA')),
    CONSTRAINT ck_usage_non_negative CHECK (confirmed_covers >= 0)
);
COMMENT ON TABLE band_capacity_usage IS 'Persone confermate (anche completate e non presentate) per giorno e fascia: budget della fascia (FIX 2)';

-- inizializzazione dalle prenotazioni esistenti: solo dati reali (annullate, rifiutate e in attesa non contano)
INSERT INTO band_capacity_usage (service_date, band, confirmed_covers)
SELECT reservation_date, service_band, sum(guest_count)
  FROM reservations
 WHERE status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
 GROUP BY reservation_date, service_band;

CREATE FUNCTION fireflies_band_usage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- nessun effetto sul budget (es. confermata → completata, stesso giorno, fascia e persone): non si tocca la riga
    IF TG_OP = 'UPDATE'
       AND (OLD.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')) = (NEW.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW'))
       AND (NOT (NEW.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW'))
            OR (OLD.reservation_date = NEW.reservation_date AND OLD.service_band = NEW.service_band
                AND OLD.guest_count = NEW.guest_count)) THEN
        RETURN NULL;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW') THEN
        UPDATE band_capacity_usage
           SET confirmed_covers = confirmed_covers - OLD.guest_count, updated_at = now()
         WHERE service_date = OLD.reservation_date AND band = OLD.service_band;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'band_capacity_usage mancante per % %', OLD.reservation_date, OLD.service_band;
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') AND NEW.status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW') THEN
        INSERT INTO band_capacity_usage (service_date, band, confirmed_covers)
        VALUES (NEW.reservation_date, NEW.service_band, NEW.guest_count)
        ON CONFLICT (service_date, band) DO UPDATE
           SET confirmed_covers = band_capacity_usage.confirmed_covers + EXCLUDED.confirmed_covers, updated_at = now();
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_res_band_usage
    AFTER INSERT OR DELETE OR UPDATE OF status, guest_count, reservation_date, starts_at, service_window_id ON reservations
    FOR EACH ROW EXECUTE FUNCTION fireflies_band_usage();

-- segnalazione (non bloccante) delle fasce già oltre la capienza del locale prima di FIX 2: restano valide
DO $$
DECLARE capacity integer; over integer;
BEGIN
    SELECT coalesce(sum(physical_capacity), 0) INTO capacity FROM capacity_zones WHERE active;
    SELECT count(*) INTO over FROM band_capacity_usage WHERE service_date >= current_date AND confirmed_covers > capacity;
    IF capacity > 0 AND over > 0 THEN
        RAISE NOTICE 'FIX 2: % fasce future hanno già più persone confermate della capienza (%): restano valide, nuove conferme bloccate', over, capacity;
    END IF;
END $$;

-- 3. Una sola richiesta attiva dal sito per telefono, giorno e fascia (anche con orari diversi nella fascia).
--    I gruppi di richieste attive già duplicati prima di FIX 2 non vengono toccati: restano com'erano ed escono dal
--    vincolo (dedup_enforced = false); le nuove richieste sono controllate contro tutte le attive.
ALTER TABLE reservations ADD COLUMN dedup_enforced boolean NOT NULL DEFAULT true;
UPDATE reservations r SET dedup_enforced = false
 WHERE r.source = 'WEB' AND r.status IN ('PENDING', 'CONFIRMED')
   AND EXISTS (SELECT 1 FROM reservations o
                WHERE o.id <> r.id AND o.source = 'WEB' AND o.status IN ('PENDING', 'CONFIRMED')
                  AND o.customer_phone_e164 = r.customer_phone_e164 AND o.reservation_date = r.reservation_date
                  AND o.service_band = r.service_band);
CREATE UNIQUE INDEX ux_res_web_band_duplicate ON reservations (customer_phone_e164, reservation_date, service_band)
    WHERE source = 'WEB' AND status IN ('PENDING', 'CONFIRMED') AND dedup_enforced;

-- 4. Eventi a qualunque minuto (non più multipli di 15'): le prenotazioni ordinarie restano a passi di 15 minuti, quelle
--    di un evento ne seguono gli orari. Il registro dei posti continua a usare quanti di 15' e un evento occupa ogni
--    quanto che tocca anche in parte (BOOKING_RULES §7). Nessun orario salvato viene cambiato.
ALTER TABLE events DROP CONSTRAINT ck_event_aligned;
ALTER TABLE events ADD CONSTRAINT ck_event_minute CHECK ((starts_at IS NULL OR extract(epoch FROM starts_at) % 60 = 0)
                                                     AND (ends_at IS NULL OR extract(epoch FROM ends_at) % 60 = 0));
ALTER TABLE reservations DROP CONSTRAINT ck_res_aligned;
ALTER TABLE reservations ADD CONSTRAINT ck_res_aligned CHECK (
    (event_id IS NULL AND extract(epoch FROM starts_at) % 900 = 0 AND extract(epoch FROM ends_at) % 900 = 0
        AND (occupied_until IS NULL OR extract(epoch FROM occupied_until) % 900 = 0))
    OR (event_id IS NOT NULL AND extract(epoch FROM starts_at) % 60 = 0 AND extract(epoch FROM ends_at) % 60 = 0
        AND (occupied_until IS NULL OR extract(epoch FROM occupied_until) % 60 = 0)));
