-- Conteggi per il reset dei dati di prova (sola lettura). Nessun dato personale: solo numeri, stati e date.
-- Colonne: sezione | tabella | dettaglio | righe
\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'
SELECT 'ELIMINA', 'reservations', 'totale', count(*) FROM reservations
UNION ALL SELECT 'ELIMINA', 'reservations', 'stato ' || status, count(*) FROM reservations GROUP BY status
UNION ALL SELECT 'ELIMINA', 'reservation_status_history', 'totale', count(*) FROM reservation_status_history
UNION ALL SELECT 'ELIMINA', 'reservation_table_assignments', 'totale', count(*) FROM reservation_table_assignments
UNION ALL SELECT 'ELIMINA', 'reservation_table_assignments', 'attive', count(*) FROM reservation_table_assignments WHERE active
UNION ALL SELECT 'ELIMINA', 'events', 'totale', count(*) FROM events
UNION ALL SELECT 'ELIMINA', 'events', 'stato ' || status, count(*) FROM events GROUP BY status
UNION ALL SELECT 'ELIMINA', 'event_media', 'collegamenti evento-foto (le foto restano)', count(*) FROM event_media
UNION ALL SELECT 'ELIMINA', 'capacity_slots', 'quanti totali (ricreati al bisogno)', count(*) FROM capacity_slots
UNION ALL SELECT 'ELIMINA', 'capacity_slots', 'quanti con persone occupate', count(*) FROM capacity_slots WHERE occupied_covers > 0
UNION ALL SELECT 'ELIMINA', 'capacity_slots', 'quanti riservati a eventi esclusivi', count(*) FROM capacity_slots WHERE exclusive_event_id IS NOT NULL
UNION ALL SELECT 'ELIMINA', 'band_capacity_usage', 'fasce con budget', count(*) FROM band_capacity_usage
UNION ALL SELECT 'ELIMINA', 'band_capacity_usage', 'fasce con budget occupato', count(*) FROM band_capacity_usage WHERE confirmed_covers > 0
UNION ALL SELECT 'ELIMINA', 'notification_outbox', 'notifiche delle prenotazioni', count(*) FROM notification_outbox WHERE aggregate_type = 'RESERVATION'
UNION ALL SELECT 'ELIMINA', 'notification_attempts', 'tentativi di quelle notifiche', count(*) FROM notification_attempts a
          JOIN notification_outbox o ON o.id = a.outbox_id WHERE o.aggregate_type = 'RESERVATION'
UNION ALL SELECT 'ELIMINA', 'whatsapp_webhook_events', 'stati WhatsApp già ricevuti (solo chiavi tecniche, V14)',
          CASE WHEN to_regclass('whatsapp_webhook_events') IS NULL THEN 0
               ELSE (xpath('/row/c/text()', query_to_xml('SELECT count(*) AS c FROM whatsapp_webhook_events', false, true, '')))[1]::text::bigint END
UNION ALL SELECT :'stats_section', 'reservation_stats', 'statistiche anonime ' || coalesce(min(service_date)::text, '-') || '..' || coalesce(max(service_date)::text, '-'), count(*) FROM reservation_stats
UNION ALL SELECT 'CONSERVA', 'notification_outbox', 'altre notifiche (non di prenotazioni)', count(*) FROM notification_outbox WHERE aggregate_type <> 'RESERVATION'
UNION ALL SELECT 'CONSERVA', 'app_users', 'ruolo ' || role || CASE WHEN active THEN ' attivi' ELSE ' disattivati' END, count(*) FROM app_users GROUP BY role, active
UNION ALL SELECT 'CONSERVA', 'menu_categories', 'totale', count(*) FROM menu_categories
UNION ALL SELECT 'CONSERVA', 'menu_items', 'totale', count(*) FROM menu_items
UNION ALL SELECT 'CONSERVA', 'menu_item_allergens', 'totale', count(*) FROM menu_item_allergens
UNION ALL SELECT 'CONSERVA', 'allergens', 'totale', count(*) FROM allergens
UNION ALL SELECT 'CONSERVA', 'media_assets', 'foto e file', count(*) FROM media_assets
UNION ALL SELECT 'CONSERVA', 'media_variants', 'varianti delle foto', count(*) FROM media_variants
UNION ALL SELECT 'CONSERVA', 'capacity_zones', 'zone attive', count(*) FROM capacity_zones WHERE active
UNION ALL SELECT 'CONSERVA', 'capacity_zones', 'capienza del locale (persone)', coalesce(sum(physical_capacity), 0) FROM capacity_zones WHERE active
UNION ALL SELECT 'CONSERVA', 'service_windows', 'fasce orarie', count(*) FROM service_windows
UNION ALL SELECT 'CONSERVA', 'opening_hours', 'orari settimanali', count(*) FROM opening_hours
UNION ALL SELECT 'CONSERVA', 'opening_exceptions', 'aperture e chiusure straordinarie', count(*) FROM opening_exceptions
UNION ALL SELECT 'CONSERVA', 'booking_exceptions', 'chiusure di fascia', count(*) FROM booking_exceptions
UNION ALL SELECT 'CONSERVA', 'booking_policies', 'regole di prenotazione', count(*) FROM booking_policies
UNION ALL SELECT 'CONSERVA', 'dining_areas', 'aree', count(*) FROM dining_areas
UNION ALL SELECT 'CONSERVA', 'dining_tables', 'tavoli', count(*) FROM dining_tables
UNION ALL SELECT 'CONSERVA', 'site_content', 'contenuti del sito', count(*) FROM site_content
UNION ALL SELECT 'CONSERVA', 'site_sections', 'sezioni della home', count(*) FROM site_sections
UNION ALL SELECT 'CONSERVA', 'site_settings', 'impostazioni', count(*) FROM site_settings
UNION ALL SELECT 'CONSERVA', 'owners', 'titolari (pagina pubblica)', count(*) FROM owners
UNION ALL SELECT 'CONSERVA', 'staff_members', 'staff (pagina pubblica)', count(*) FROM staff_members
UNION ALL SELECT 'CONSERVA', 'hero_slides', 'foto del menu', count(*) FROM hero_slides
UNION ALL SELECT 'CONSERVA', 'announcements', 'avvisi', count(*) FROM announcements
UNION ALL SELECT 'CONSERVA', 'notification_templates', 'modelli dei messaggi', count(*) FROM notification_templates
UNION ALL SELECT 'CONSERVA', 'audit_logs', 'registro delle operazioni (resta come traccia)', count(*) FROM audit_logs
UNION ALL SELECT 'CONSERVA', 'maintenance_runs', 'esecuzioni della pulizia notturna', count(*) FROM maintenance_runs
UNION ALL SELECT 'CONSERVA', 'media_orphans', 'file da rimuovere in coda', count(*) FROM media_orphans
UNION ALL SELECT 'CONSERVA', 'spring_session', 'sessioni dei gestori', count(*) FROM spring_session
UNION ALL SELECT 'CONSERVA', 'flyway_schema_history', 'migrazioni riuscite', count(*) FROM flyway_schema_history WHERE success
ORDER BY 1 DESC, 2, 3;
