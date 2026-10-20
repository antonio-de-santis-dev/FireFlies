# Prova di ripristino — 2026-10-20T18:26Z, commit 9beade5

```
OK: 36 tabelle identiche (allergens=14 announcements=2 app_users=0 audit_logs=118 booking_exceptions=0 booking_policies=2 capacity_slots=14 capacity_zones=1 dining_areas=2 dining_tables=10 event_media=8 events=3 flyway_schema_history=11 hero_slides=5 maintenance_runs=0 media_assets=32 media_orphans=0 media_variants=87 menu_categories=6 menu_item_allergens=0 menu_items=15 notification_attempts=0 notification_outbox=1 notification_templates=12 opening_exceptions=0 opening_hours=7 owners=3 reservation_stats=0 reservation_status_history=1 reservation_table_assignments=0 reservations=1 service_windows=28 site_content=1 site_sections=6 site_settings=1 staff_members=3)
OK: check-health segnala la pulizia mancante e passa con backup recente, manifest valido e backend pronto
OK: foto /media/1e98955e-5f27-4676-84aa-c3f6f026507a/11c2c42d4a23/w960.jpg identica (sha256 00180eaa356a934bcba26016c861ab5a1d588377eca8aa315d91a679c3729cbb), prenotazione FF-NJ47BE in attesa, 11 migrazioni Flyway valide, backend avviato su B
```

Comando: `infra/scripts/restore-drill.sh` (due PostgreSQL isolati sulle porte 55511/55512, eliminati alla fine; database di sviluppo non toccato). Comprende V11 (11 migrazioni) e il controllo `check-health.sh`: segnala la pulizia notturna mancante (spenta nella prova) e passa dopo un'esecuzione completata.

Prima esecuzione: 2026-10-20T17:57Z sul commit `d5468ca` + modifiche FASE 8 (10 migrazioni), stesso esito.
