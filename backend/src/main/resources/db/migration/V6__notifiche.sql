-- FireFlies Aperitif — V6: notifiche con transactional outbox (semantica at-least-once).
-- Riferimento: DATA_MODEL.md §3.8; ADR-0005. Nessun invio reale: in sviluppo e test solo gateway fake.

CREATE TABLE notification_templates (
    code    varchar(40)  NOT NULL,
    channel text         NOT NULL,
    subject varchar(120),
    body    text         NOT NULL,
    active  boolean      NOT NULL DEFAULT false,
    version integer      NOT NULL DEFAULT 0,
    PRIMARY KEY (code, channel),
    CONSTRAINT ck_template_channel  CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT ck_template_sms_size CHECK (channel <> 'SMS' OR char_length(body) <= 320)
);

CREATE TABLE notification_outbox (
    id                       uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    business_event_key       varchar(160) NOT NULL CONSTRAINT ux_outbox_business_key UNIQUE,
    aggregate_type           varchar(30)  NOT NULL,
    aggregate_id             uuid         NOT NULL,
    channel                  text         NOT NULL,
    recipient_kind           text         NOT NULL,
    recipient                varchar(254),
    template_code            varchar(40)  NOT NULL,
    payload                  jsonb,
    state                    text         NOT NULL DEFAULT 'PENDING',
    skip_reason              varchar(40),
    attempts                 smallint     NOT NULL DEFAULT 0,
    next_attempt_at          timestamptz  NOT NULL DEFAULT now(),
    lease_token              uuid,
    locked_until             timestamptz,
    provider_idempotency_key varchar(80)  NOT NULL DEFAULT gen_random_uuid()::text,
    manual_resend_count      smallint     NOT NULL DEFAULT 0,
    expires_at               timestamptz,
    last_error_code          varchar(60),
    created_at               timestamptz  NOT NULL DEFAULT now(),
    sent_at                  timestamptz,
    purge_after              timestamptz,
    CONSTRAINT fk_outbox_template FOREIGN KEY (template_code, channel) REFERENCES notification_templates (code, channel),
    CONSTRAINT ck_outbox_channel   CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT ck_outbox_recipient CHECK (recipient_kind IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT ck_outbox_state     CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'DELIVERED', 'UNCERTAIN', 'FAILED', 'DEAD', 'SKIPPED')),
    CONSTRAINT ck_outbox_attempts  CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT ck_outbox_resends   CHECK (manual_resend_count >= 0),
    -- Chi sta inviando ha sempre un lease: un worker con lease scaduto non può più registrare l'esito.
    CONSTRAINT ck_outbox_lease     CHECK (state <> 'SENDING' OR (lease_token IS NOT NULL AND locked_until IS NOT NULL)),
    CONSTRAINT ck_outbox_skipped   CHECK (state <> 'SKIPPED' OR skip_reason IS NOT NULL)
);
CREATE INDEX ix_outbox_ready     ON notification_outbox (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX ix_outbox_lease     ON notification_outbox (locked_until) WHERE state = 'SENDING';
CREATE INDEX ix_outbox_attention ON notification_outbox (created_at) WHERE state IN ('UNCERTAIN', 'FAILED', 'DEAD');
CREATE INDEX ix_outbox_aggregate ON notification_outbox (aggregate_type, aggregate_id);

CREATE TABLE notification_attempts (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    outbox_id            uuid        NOT NULL CONSTRAINT fk_attempt_outbox REFERENCES notification_outbox (id) ON DELETE CASCADE,
    attempt_no           smallint    NOT NULL,
    provider             varchar(30) NOT NULL,
    idempotency_key_used varchar(80),
    started_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz,
    outcome              text,
    http_status          smallint,
    provider_message_id  varchar(120),
    error_code           varchar(60),
    duration_ms          integer,
    CONSTRAINT ux_attempt_number   UNIQUE (outbox_id, attempt_no),
    CONSTRAINT ck_attempt_outcome  CHECK (outcome IS NULL OR outcome IN ('ACCEPTED', 'REJECTED_PERMANENT', 'TRANSIENT_ERROR', 'UNKNOWN')),
    CONSTRAINT ck_attempt_finished CHECK ((outcome IS NULL) = (finished_at IS NULL))
);
