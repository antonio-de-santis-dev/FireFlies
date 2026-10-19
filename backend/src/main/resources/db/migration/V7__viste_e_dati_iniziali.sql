-- FireFlies Aperitif — V7: vista degli usi delle foto e dati iniziali NON demo.
-- Solo valori già confermati dal committente o configurazioni spente: nessun dato inventato.

-- Dove è usata una foto (alimenta il messaggio «è usata in…» dell'area gestori).
CREATE VIEW media_usages_v AS
    SELECT photo_media_id AS media_id, 'MENU_ITEM' AS usage_type, id AS object_id, name AS label
      FROM menu_items WHERE photo_media_id IS NOT NULL
    UNION ALL
    SELECT poster_media_id, 'EVENT_POSTER', id, title FROM events WHERE poster_media_id IS NOT NULL
    UNION ALL
    SELECT em.media_id, 'EVENT_GALLERY', e.id, e.title FROM event_media em JOIN events e ON e.id = em.event_id
    UNION ALL
    SELECT media_id, 'HERO_' || page, id, page FROM hero_slides
    UNION ALL
    SELECT front_media_id, 'OWNER_FRONT', id, name FROM owners WHERE front_media_id IS NOT NULL
    UNION ALL
    SELECT back_media_id, 'OWNER_BACK', id, name FROM owners WHERE back_media_id IS NOT NULL
    UNION ALL
    SELECT media_id, 'STAFF', id, name FROM staff_members WHERE media_id IS NOT NULL
    UNION ALL
    SELECT hero_media_id, 'HOME_HERO', NULL, 'Home' FROM site_content WHERE hero_media_id IS NOT NULL;

-- Impostazioni: indirizzo confermato il 19/10/2026; telefono ed email non ancora forniti; SMS spenti.
INSERT INTO site_settings (id, address_line, map_query, map_mode, instagram_url, address_confirmed, hours_confirmed, sms_enabled)
VALUES (1,
        'Via Giovanni XXIII, 19 — 73025 Martano (LE)',
        'FireFlies Aperitif, Via Giovanni XXIII, 19, 73025 Martano LE',
        'CONSENT',
        'https://www.instagram.com/fireflies.aperitif/',
        true, false, false);

-- Contenuti: vuoti finché il titolare non li scrive dall'area gestori.
INSERT INTO site_content (id) VALUES (1);

-- Sezioni della home come nel mockup approvato.
INSERT INTO site_sections (code, enabled, sort_order) VALUES
    ('momento', true, 1), ('trio', true, 2), ('persone', true, 3),
    ('eventi', true, 4), ('striscia', true, 5), ('visita', true, 6);

-- Zona predefinita «Locale»: SPENTA e con capienza 0 finché il titolare non imposta quella reale (Q-02).
INSERT INTO capacity_zones (name, physical_capacity, active, sort_order) VALUES ('Locale', 0, false, 1);

-- Regole generali: prenotazioni dal sito DISATTIVATE. Valori di esempio da sostituire con quelli dei titolari.
INSERT INTO booking_policies (effective_from, confirmation_mode, web_booking_enabled, min_lead_minutes,
                              max_advance_days, max_party_size_web, pending_expiry_minutes_before_start)
VALUES (DATE '2026-01-01', 'MANUAL', false, 60, 60, 12, 60);

-- Bozze dei testi delle notifiche: INATTIVE finché il titolare non le approva (FASE 7).
-- Segnaposto: {{nome}}, {{data}}, {{ora}}, {{persone}}, {{riferimento}}, {{telefono_locale}}.
INSERT INTO notification_templates (code, channel, subject, body, active) VALUES
    ('RESERVATION_NEW_FOR_VENUE', 'EMAIL', 'Nuova richiesta di prenotazione {{riferimento}}',
     'Nuova richiesta dal sito: {{persone}} persone il {{data}} alle {{ora}}. Riferimento {{riferimento}}. Gestiscila dall''area gestori.', false),
    ('RESERVATION_RECEIVED', 'EMAIL', 'Abbiamo ricevuto la tua richiesta',
     'Ciao {{nome}}, abbiamo ricevuto la tua richiesta per {{persone}} persone il {{data}} alle {{ora}} (rif. {{riferimento}}). Non è ancora una prenotazione: ti confermeremo al più presto. FireFlies Aperitif', false),
    ('RESERVATION_CONFIRMED', 'EMAIL', 'Prenotazione confermata',
     'Ciao {{nome}}, la tua prenotazione per {{persone}} persone il {{data}} alle {{ora}} è confermata (rif. {{riferimento}}). Per modifiche chiamaci al {{telefono_locale}}. FireFlies Aperitif', false),
    ('RESERVATION_CONFIRMED', 'SMS', NULL,
     'FireFlies: prenotazione confermata per {{persone}} il {{data}} alle {{ora}}. Rif. {{riferimento}}. Modifiche: {{telefono_locale}}', false),
    ('RESERVATION_REJECTED', 'EMAIL', 'Non possiamo confermare la tua richiesta',
     'Ciao {{nome}}, purtroppo non possiamo confermare la richiesta per il {{data}} alle {{ora}} (rif. {{riferimento}}). Chiamaci al {{telefono_locale}} per trovare un''alternativa. FireFlies Aperitif', false),
    ('RESERVATION_REJECTED', 'SMS', NULL,
     'FireFlies: non possiamo confermare la richiesta del {{data}} alle {{ora}} (rif. {{riferimento}}). Chiamaci: {{telefono_locale}}', false),
    ('RESERVATION_EXPIRED', 'EMAIL', 'Non siamo riusciti a confermare in tempo',
     'Ciao {{nome}}, non siamo riusciti a confermare in tempo la richiesta per il {{data}} alle {{ora}} (rif. {{riferimento}}). Chiamaci al {{telefono_locale}}. FireFlies Aperitif', false),
    ('RESERVATION_EXPIRED', 'SMS', NULL,
     'FireFlies: non siamo riusciti a confermare in tempo la richiesta del {{data}} alle {{ora}} (rif. {{riferimento}}). Chiamaci: {{telefono_locale}}', false),
    ('RESERVATION_CANCELLED', 'EMAIL', 'Prenotazione annullata',
     'Ciao {{nome}}, la prenotazione del {{data}} alle {{ora}} (rif. {{riferimento}}) è stata annullata. Per informazioni chiamaci al {{telefono_locale}}. FireFlies Aperitif', false),
    ('RESERVATION_CANCELLED', 'SMS', NULL,
     'FireFlies: la prenotazione del {{data}} alle {{ora}} (rif. {{riferimento}}) è annullata. Info: {{telefono_locale}}', false),
    ('RESERVATION_UPDATED', 'EMAIL', 'Prenotazione aggiornata',
     'Ciao {{nome}}, la tua prenotazione è stata aggiornata: {{persone}} persone il {{data}} alle {{ora}} (rif. {{riferimento}}). FireFlies Aperitif', false),
    ('RESERVATION_UPDATED', 'SMS', NULL,
     'FireFlies: prenotazione aggiornata, {{persone}} persone il {{data}} alle {{ora}}. Rif. {{riferimento}}', false);
