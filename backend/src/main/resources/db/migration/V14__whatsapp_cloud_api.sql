-- FireFlies Aperitif — V14: conferma della prenotazione su WhatsApp (Meta WhatsApp Cloud API), solo messaggi di servizio.
-- Migrazione incrementale e non distruttiva: nessuna riga esistente cambia significato, nessun consenso presunto.
-- Riferimento: docs/architecture/WHATSAPP_CLOUD_API_REPORT.md.

-- 1. Canale WHATSAPP accanto a EMAIL e SMS (stesso outbox, stesso worker).
ALTER TABLE notification_templates DROP CONSTRAINT ck_template_channel;
ALTER TABLE notification_templates
    ADD CONSTRAINT ck_template_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP'));
ALTER TABLE notification_outbox DROP CONSTRAINT ck_outbox_channel;
ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_outbox_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP'));

-- Il testo vero è il modello approvato da Meta (nome e lingua da configurazione, fuori dal codice): questa riga serve
-- al vincolo fk_outbox_template e come anteprima leggibile in area gestori. Parametri: {{1}} nome, {{2}} data,
-- {{3}} ora, {{4}} persone. L'invio dipende dalla modalità FIREFLIES_WHATSAPP_MODE, non dal flag active.
INSERT INTO notification_templates (code, channel, subject, body, active) VALUES
    ('RESERVATION_CONFIRMED', 'WHATSAPP', NULL,
     'Ciao {{nome}}, la tua prenotazione da FireFlies Aperitif per il {{data}} alle {{ora}} per {{persone}} persone è confermata.', false)
ON CONFLICT (code, channel) DO NOTHING;

-- 2. Consenso esplicito del cliente a ricevere la conferma su WhatsApp, separato dall'informativa privacy.
-- Le prenotazioni esistenti restano senza consenso (NULL): nessun messaggio WhatsApp per loro.
ALTER TABLE reservations
    ADD COLUMN whatsapp_consent_at           timestamptz,
    ADD COLUMN whatsapp_consent_version      varchar(20),
    ADD COLUMN whatsapp_consent_withdrawn_at timestamptz,
    ADD CONSTRAINT ck_res_whatsapp_consent CHECK ((whatsapp_consent_at IS NULL) = (whatsapp_consent_version IS NULL)),
    ADD CONSTRAINT ck_res_whatsapp_withdrawn CHECK (whatsapp_consent_withdrawn_at IS NULL OR whatsapp_consent_at IS NOT NULL);
COMMENT ON COLUMN reservations.whatsapp_consent_at IS
    'Quando il cliente ha spuntato la casella WhatsApp (mai preselezionata). NULL = nessun consenso.';
COMMENT ON COLUMN reservations.whatsapp_consent_version IS 'Versione del testo della casella accettato (es. WA-1).';
COMMENT ON COLUMN reservations.whatsapp_consent_withdrawn_at IS 'Revoca del consenso: da qui in poi nessun messaggio WhatsApp.';

-- 3. Esito lato Meta: id del messaggio (correlazione con il webhook) e stato di consegna, mai regressivo.
ALTER TABLE notification_outbox
    ADD COLUMN provider_message_id varchar(120),
    ADD COLUMN delivery_status     varchar(20),
    ADD COLUMN delivery_updated_at timestamptz,
    ADD COLUMN delivery_error_code varchar(60),
    ADD CONSTRAINT ck_outbox_delivery_status CHECK (delivery_status IS NULL OR delivery_status IN ('SENT', 'DELIVERED', 'READ', 'FAILED'));
CREATE UNIQUE INDEX ux_outbox_provider_message ON notification_outbox (provider_message_id) WHERE provider_message_id IS NOT NULL;

-- 4. Eventi del webhook già visti (deduplica): solo una chiave tecnica e l'ora, nessun numero né contenuto.
CREATE TABLE whatsapp_webhook_events (
    event_key   varchar(200) PRIMARY KEY,
    received_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_whatsapp_webhook_received ON whatsapp_webhook_events (received_at);
