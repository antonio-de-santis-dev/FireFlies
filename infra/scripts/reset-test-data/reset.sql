-- Reset dei dati di prova di prenotazioni ed eventi (FIX 2). Eseguito da reset.sh con
--   psql -v ON_ERROR_STOP=1 --single-transaction -v keep_stats=<true|false>
-- Tutto in UNA transazione: se un passo fallisce (vincolo, trigger, controllo finale) non cambia nulla.
-- Nessun TRUNCATE, nessun vincolo o trigger disattivato: i DELETE seguono l'ordine delle foreign key e il trigger
-- del budget per fascia scala le persone delle confermate come in un'operazione normale.

-- lock in ordine: nessuno può scrivere prenotazioni, eventi o registro mentre si azzera
LOCK TABLE reservations, reservation_status_history, reservation_table_assignments, events, event_media,
           capacity_slots, band_capacity_usage, notification_outbox, notification_attempts, reservation_stats
    IN EXCLUSIVE MODE;

-- 1. notifiche delle prenotazioni e i loro tentativi (outbox senza foreign key verso le prenotazioni)
DELETE FROM notification_attempts a USING notification_outbox o
 WHERE a.outbox_id = o.id AND o.aggregate_type = 'RESERVATION';
DELETE FROM notification_outbox WHERE aggregate_type = 'RESERVATION';
-- V14: chiavi di deduplica del webhook WhatsApp (nessun dato personale), solo se la tabella esiste
DO $$ BEGIN
    IF to_regclass('whatsapp_webhook_events') IS NOT NULL THEN
        DELETE FROM whatsapp_webhook_events;
    END IF;
END $$;

-- 2. figli delle prenotazioni (fk_assign_reservation, fk_history_reservation: ON DELETE RESTRICT)
DELETE FROM reservation_table_assignments;
DELETE FROM reservation_status_history;

-- 3. prenotazioni: il trigger trg_res_band_usage restituisce il budget di ogni confermata/completata/non presentata
DELETE FROM reservations;

-- 4. budget per fascia: dopo il passo 3 ogni riga è a zero; si tolgono le righe
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM band_capacity_usage WHERE confirmed_covers <> 0) THEN
        RAISE EXCEPTION 'budget per fascia non a zero dopo aver tolto le prenotazioni: dati incoerenti, nulla modificato';
    END IF;
END $$;
DELETE FROM band_capacity_usage;

-- 5. registro dei posti: quanti occupati e riserve esclusive (fk_slot_event verso events). I quanti si ricreano al
--    bisogno con la capienza attuale del locale.
DELETE FROM capacity_slots;

-- 6. eventi: prima i collegamenti alle foto (le foto in media_assets restano), poi gli eventi
DELETE FROM event_media;
DELETE FROM events;

-- 7. statistiche anonime ricavate da prenotazioni già pulite (solo dati locali di prova)
DELETE FROM reservation_stats WHERE NOT :keep_stats;

-- controllo finale nella stessa transazione: se qualcosa non torna, ROLLBACK di tutto
DO $$ BEGIN
    IF (SELECT count(*) FROM reservations) + (SELECT count(*) FROM events) + (SELECT count(*) FROM capacity_slots)
       + (SELECT count(*) FROM band_capacity_usage) + (SELECT count(*) FROM reservation_table_assignments)
       + (SELECT count(*) FROM reservation_status_history) + (SELECT count(*) FROM event_media)
       + (SELECT count(*) FROM notification_outbox WHERE aggregate_type = 'RESERVATION') > 0 THEN
        RAISE EXCEPTION 'controllo finale fallito: restano righe da eliminare, nulla modificato';
    END IF;
END $$;
