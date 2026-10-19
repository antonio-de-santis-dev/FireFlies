-- FireFlies Aperitif — V5: prenotazioni, storico stati, assegnazioni manuali dei tavoli.
-- Riferimento: DATA_MODEL.md §3.4; BOOKING_RULES.md §2, §3, §8.
-- Nessuna assegnazione automatica di tavoli: la tabella delle assegnazioni la scrive solo il gestore.

CREATE TABLE reservations (
    id                     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    public_ref             varchar(12)  NOT NULL CONSTRAINT ux_res_public_ref UNIQUE,
    status                 text         NOT NULL DEFAULT 'PENDING',
    status_reason          text,
    source                 text         NOT NULL,
    reservation_date       date         NOT NULL,
    starts_at              timestamptz  NOT NULL,
    ends_at                timestamptz  NOT NULL,
    occupied_until         timestamptz,
    guest_count            smallint     NOT NULL,
    service_window_id      uuid         CONSTRAINT fk_res_window REFERENCES service_windows (id) ON DELETE RESTRICT,
    event_id               uuid         CONSTRAINT fk_res_event  REFERENCES events (id) ON DELETE RESTRICT,
    capacity_zone_id       uuid         CONSTRAINT fk_res_zone   REFERENCES capacity_zones (id) ON DELETE RESTRICT,
    customer_name          varchar(80),
    customer_phone_e164    varchar(16),
    customer_email         varchar(254),
    customer_notes         varchar(500),
    internal_note          varchar(500),
    needs_callback         boolean      NOT NULL DEFAULT false,
    privacy_notice_version varchar(20),
    privacy_ack_at         timestamptz,
    idempotency_key        uuid         CONSTRAINT ux_res_idempotency_key UNIQUE,
    request_fingerprint    char(64),
    created_by_user_id     uuid         CONSTRAINT fk_res_created_by REFERENCES app_users (id) ON DELETE SET NULL,
    handled_by_user_id     uuid         CONSTRAINT fk_res_handled_by REFERENCES app_users (id) ON DELETE SET NULL,
    confirmed_at           timestamptz,
    closed_at              timestamptz,
    anonymized_at          timestamptz,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    version                integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_res_status        CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT ck_res_status_reason CHECK (status_reason IS NULL OR status_reason IN
                                          ('EXPIRED', 'NO_CAPACITY', 'CUSTOMER_REQUEST', 'VENUE_DECISION', 'EVENT_CANCELLED', 'DUPLICATE', 'OTHER')),
    CONSTRAINT ck_res_source        CHECK (source IN ('WEB', 'PHONE', 'EMAIL')),
    CONSTRAINT ck_res_time_order    CHECK (ends_at > starts_at),
    CONSTRAINT ck_res_aligned       CHECK (extract(epoch FROM starts_at) % 900 = 0 AND extract(epoch FROM ends_at) % 900 = 0
                                          AND (occupied_until IS NULL OR extract(epoch FROM occupied_until) % 900 = 0)),
    CONSTRAINT ck_res_occupied_until CHECK (occupied_until IS NULL OR (occupied_until > starts_at AND occupied_until <= ends_at)),
    CONSTRAINT ck_res_guests        CHECK (guest_count BETWEEN 1 AND 50),
    CONSTRAINT ck_res_phone         CHECK (customer_phone_e164 IS NULL OR customer_phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_res_target        CHECK (event_id IS NOT NULL OR service_window_id IS NOT NULL),
    -- Chi occupa posti ha sempre zona e intervallo di occupazione.
    CONSTRAINT ck_res_occupying     CHECK (status NOT IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')
                                          OR (capacity_zone_id IS NOT NULL AND occupied_until IS NOT NULL)),
    CONSTRAINT ck_res_web_privacy   CHECK (source <> 'WEB' OR (privacy_ack_at IS NOT NULL AND privacy_notice_version IS NOT NULL)),
    CONSTRAINT ck_res_anonymized    CHECK (anonymized_at IS NOT NULL OR (customer_phone_e164 IS NOT NULL AND customer_name IS NOT NULL))
);
-- Doppio invio dal sito: stesso telefono e stesso orario ancora attivo.
CREATE UNIQUE INDEX ux_res_web_duplicate ON reservations (customer_phone_e164, starts_at)
    WHERE source = 'WEB' AND status IN ('PENDING', 'CONFIRMED');
CREATE INDEX ix_res_date_status   ON reservations (reservation_date, status);
CREATE INDEX ix_res_pending_start ON reservations (starts_at) WHERE status = 'PENDING';
CREATE INDEX ix_res_zone_time     ON reservations USING gist (capacity_zone_id, tstzrange(starts_at, occupied_until))
    WHERE status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW');
CREATE INDEX ix_res_event         ON reservations (event_id, status) WHERE event_id IS NOT NULL;
CREATE INDEX ix_res_phone         ON reservations (customer_phone_e164);
CREATE INDEX ix_res_created       ON reservations (created_at DESC);

CREATE TABLE reservation_table_assignments (
    id                    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id        uuid         NOT NULL CONSTRAINT fk_assign_reservation REFERENCES reservations (id) ON DELETE RESTRICT,
    table_id              uuid         NOT NULL CONSTRAINT fk_assign_table REFERENCES dining_tables (id) ON DELETE RESTRICT,
    occupied              tstzrange    NOT NULL,
    active                boolean      NOT NULL DEFAULT true,
    seats_override        boolean      NOT NULL DEFAULT false,
    seats_override_by     uuid         CONSTRAINT fk_assign_override_by REFERENCES app_users (id) ON DELETE RESTRICT,
    seats_override_reason varchar(200),
    assigned_by           uuid         CONSTRAINT fk_assign_by REFERENCES app_users (id) ON DELETE SET NULL,
    assigned_at           timestamptz  NOT NULL DEFAULT now(),
    released_at           timestamptz,
    CONSTRAINT ck_assign_range    CHECK (lower_inc(occupied) AND NOT upper_inc(occupied) AND NOT isempty(occupied)
                                         AND NOT lower_inf(occupied) AND NOT upper_inf(occupied)),
    -- Eccezione posti: solo con autore e motivo (il ruolo OWNER è verificato dall'applicazione e registrato nell'audit).
    CONSTRAINT ck_override_complete CHECK (NOT seats_override
                                         OR (seats_override_by IS NOT NULL AND seats_override_reason IS NOT NULL
                                             AND length(trim(seats_override_reason)) > 0)),
    -- Nessun tavolo con due assegnazioni attive sovrapposte nel tempo.
    CONSTRAINT ex_table_no_overlap EXCLUDE USING gist (table_id WITH =, occupied WITH &&) WHERE (active)
);
CREATE UNIQUE INDEX ux_assignment_once ON reservation_table_assignments (reservation_id, table_id) WHERE active;
CREATE INDEX ix_assign_reservation ON reservation_table_assignments (reservation_id);

CREATE TABLE reservation_status_history (
    id                 bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id     uuid         NOT NULL CONSTRAINT fk_history_reservation REFERENCES reservations (id) ON DELETE RESTRICT,
    from_status        text,
    to_status          text         NOT NULL,
    reason             text,
    note               varchar(300),
    changed_by_user_id uuid         CONSTRAINT fk_history_user REFERENCES app_users (id) ON DELETE SET NULL,
    changed_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_history_reservation ON reservation_status_history (reservation_id, changed_at);
