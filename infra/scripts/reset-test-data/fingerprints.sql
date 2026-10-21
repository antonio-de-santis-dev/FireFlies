-- Impronte (md5) del contenuto delle tabelle da conservare: prima e dopo il reset devono coincidere.
-- Stampa solo l'impronta, mai il contenuto (nessuna password, nessun dato personale).
\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'
SELECT 'app_users', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM app_users t
UNION ALL SELECT 'menu_categories', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM menu_categories t
UNION ALL SELECT 'menu_items', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM menu_items t
UNION ALL SELECT 'menu_item_allergens', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM menu_item_allergens t
UNION ALL SELECT 'allergens', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM allergens t
UNION ALL SELECT 'media_assets', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM media_assets t
UNION ALL SELECT 'media_variants', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM media_variants t
UNION ALL SELECT 'capacity_zones', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM capacity_zones t
UNION ALL SELECT 'service_windows', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM service_windows t
UNION ALL SELECT 'opening_hours', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM opening_hours t
UNION ALL SELECT 'opening_exceptions', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM opening_exceptions t
UNION ALL SELECT 'booking_exceptions', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM booking_exceptions t
UNION ALL SELECT 'booking_policies', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM booking_policies t
UNION ALL SELECT 'dining_areas', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM dining_areas t
UNION ALL SELECT 'dining_tables', md5(coalesce(string_agg(t::text, '§' ORDER BY t.id), '')) FROM dining_tables t
UNION ALL SELECT 'site_content', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM site_content t
UNION ALL SELECT 'site_sections', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM site_sections t
UNION ALL SELECT 'site_settings', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM site_settings t
UNION ALL SELECT 'owners', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM owners t
UNION ALL SELECT 'staff_members', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM staff_members t
UNION ALL SELECT 'hero_slides', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM hero_slides t
UNION ALL SELECT 'announcements', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM announcements t
UNION ALL SELECT 'notification_templates', md5(coalesce(string_agg(t::text, '§' ORDER BY t::text), '')) FROM notification_templates t
UNION ALL SELECT 'flyway_schema_history', md5(coalesce(string_agg(t::text, '§' ORDER BY t.installed_rank), '')) FROM flyway_schema_history t
ORDER BY 1;
