-- FireFlies Aperitif — V9: completamenti per la FASE 4 (menu, foto, contenuti).
-- Riferimento: FASE4_REPORT.md §3. Nessuna tabella esistente perde dati: solo colonne nuove e una tabella di servizio.

-- Formato di ogni variante: JPEG per le foto, PNG per le immagini con trasparenza (es. i titolari scontornati).
ALTER TABLE media_variants
    ADD COLUMN mime_type text NOT NULL DEFAULT 'image/jpeg'
        CONSTRAINT ck_variant_mime CHECK (mime_type IN ('image/jpeg', 'image/png'));
ALTER TABLE media_variants ALTER COLUMN mime_type DROP DEFAULT;

-- File dello storage da cancellare di nuovo: la cancellazione nello storage avviene dopo il commit e può fallire
-- (rete, provider). Un job li riprova; il database resta la fonte di verità.
CREATE TABLE media_orphans (
    storage_key varchar(250) PRIMARY KEY,
    reason      varchar(40)  NOT NULL,
    attempts    integer      NOT NULL DEFAULT 0,
    last_error  varchar(250),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    next_try_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_media_orphans_next ON media_orphans (next_try_at);

-- Prezzo DIMOSTRATIVO: solo nei dati demo locali (P-20h), mai confuso con il prezzo reale.
ALTER TABLE menu_items
    ADD COLUMN demo_price_cents integer
        CONSTRAINT ck_item_demo_price CHECK (demo_price_cents IS NULL OR demo_price_cents BETWEEN 0 AND 100000);
CREATE INDEX ix_menu_items_photo ON menu_items (photo_media_id) WHERE photo_media_id IS NOT NULL;

-- Conferma dei testi di titolari e staff (etichetta «da confermare» sul sito, come per la storia).
ALTER TABLE site_content
    ADD COLUMN owners_confirmed boolean NOT NULL DEFAULT false,
    ADD COLUMN staff_confirmed  boolean NOT NULL DEFAULT false;

-- Segnale dei dati dimostrativi: lo imposta solo il caricatore demo del profilo «local».
-- Con il profilo «prod» l'applicazione si rifiuta di partire se è true (DemoDataGuard).
ALTER TABLE site_settings
    ADD COLUMN demo_content boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN site_settings.demo_content IS 'true = il database contiene dati dimostrativi: vietato in produzione.';
