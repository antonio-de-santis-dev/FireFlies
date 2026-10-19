-- FireFlies Aperitif — V1: estensioni, utenti dell'area gestori, registro azioni.
-- Riferimento: docs/architecture/DATA_MODEL.md §1, §3.1.
-- Le tabelle di Spring Session arrivano con la FASE 3 (autenticazione).

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE app_users (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               varchar(254) NOT NULL,
    password_hash       varchar(255) NOT NULL,
    display_name        varchar(80)  NOT NULL,
    role                text         NOT NULL,
    active              boolean      NOT NULL DEFAULT true,
    failed_login_count  integer      NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    password_changed_at timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_users_role   CHECK (role IN ('OWNER', 'STAFF')),
    CONSTRAINT ck_app_users_failed CHECK (failed_login_count >= 0)
);
CREATE UNIQUE INDEX ux_app_users_email ON app_users (lower(email));

CREATE TABLE audit_logs (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid        CONSTRAINT fk_audit_actor REFERENCES app_users (id) ON DELETE SET NULL,
    action        varchar(60) NOT NULL,
    object_type   varchar(40) NOT NULL,
    object_id     varchar(64) NOT NULL,
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_object   ON audit_logs (object_type, object_id, occurred_at DESC);
CREATE INDEX ix_audit_occurred ON audit_logs (occurred_at);

COMMENT ON COLUMN audit_logs.metadata IS 'Mai dati personali di clienti (nomi, telefoni, email, note).';
