-- FireFlies Aperitif — V4: zone di capienza, aree e tavoli, fasce prenotabili, regole, eventi,
-- registro di occupazione a quanti da 15 minuti.
-- Riferimento: DATA_MODEL.md §3.2, §3.3, §3.5; BOOKING_RULES.md §3, §7; ADR-0006.
-- Un orario è "allineato" se è un multiplo esatto di 15 minuti (900 s) dall'epoch: indipendente dal fuso,
-- perché Europe/Rome ha offset a ore intere.

-- Spazio fisicamente separato con capienza propria (persone presenti contemporaneamente).
CREATE TABLE capacity_zones (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name              varchar(40) NOT NULL,
    physical_capacity integer     NOT NULL,
    active            boolean     NOT NULL DEFAULT false,
    sort_order        integer     NOT NULL DEFAULT 0,
    version           integer     NOT NULL DEFAULT 0,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_zone_capacity CHECK (physical_capacity BETWEEN 0 AND 1000)
);
CREATE UNIQUE INDEX ux_zone_name ON capacity_zones (lower(name));

-- Raggruppamento di tavoli: nessuna capienza propria.
CREATE TABLE dining_areas (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_id    uuid        NOT NULL CONSTRAINT fk_area_zone REFERENCES capacity_zones (id) ON DELETE RESTRICT,
    name       varchar(60) NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    sort_order integer     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_area_name ON dining_areas (lower(name));

-- Nessuna colonna "combinable": la combinazione di tavoli è V2.
CREATE TABLE dining_tables (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id    uuid        NOT NULL CONSTRAINT fk_table_area REFERENCES dining_areas (id) ON DELETE RESTRICT,
    code       varchar(10) NOT NULL,
    min_seats  smallint    NOT NULL,
    max_seats  smallint    NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    sort_order integer     NOT NULL DEFAULT 0,
    CONSTRAINT ux_table_code   UNIQUE (area_id, code),
    CONSTRAINT ck_table_seats  CHECK (min_seats >= 1 AND max_seats >= min_seats AND max_seats <= 30)
);

-- Fasce PRENOTABILI (distinte dagli orari pubblici).
CREATE TABLE service_windows (
    id                       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_id                  uuid        NOT NULL CONSTRAINT fk_window_zone REFERENCES capacity_zones (id) ON DELETE RESTRICT,
    iso_day                  smallint    NOT NULL,
    name                     varchar(40) NOT NULL,
    first_start              time        NOT NULL,
    last_start               time        NOT NULL,
    slot_minutes             smallint    NOT NULL,
    default_duration_minutes smallint    NOT NULL,
    max_covers               integer,
    valid_from               date        NOT NULL,
    valid_to                 date,
    active                   boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_window_day      CHECK (iso_day BETWEEN 1 AND 7),
    CONSTRAINT ck_window_order    CHECK (last_start >= first_start),
    CONSTRAINT ck_window_aligned  CHECK (extract(epoch FROM first_start) % 900 = 0 AND extract(epoch FROM last_start) % 900 = 0),
    CONSTRAINT ck_window_step     CHECK (slot_minutes IN (15, 30, 60)),
    CONSTRAINT ck_window_duration CHECK (default_duration_minutes BETWEEN 30 AND 360 AND default_duration_minutes % 15 = 0),
    CONSTRAINT ck_window_covers   CHECK (max_covers IS NULL OR max_covers >= 0),
    CONSTRAINT ck_window_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_windows_zone_day ON service_windows (zone_id, iso_day, active);
COMMENT ON COLUMN service_windows.max_covers IS 'Limite di presenze contemporanee nella fascia; NULL = capienza fisica della zona.';

-- Regole generali con data di validità. In V1 la conferma è SOLO manuale.
CREATE TABLE booking_policies (
    id                                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    effective_from                      date        NOT NULL CONSTRAINT ux_policy_effective_from UNIQUE,
    confirmation_mode                   text        NOT NULL DEFAULT 'MANUAL',
    web_booking_enabled                 boolean     NOT NULL DEFAULT false,
    min_lead_minutes                    integer     NOT NULL,
    max_advance_days                    integer     NOT NULL,
    max_party_size_web                  smallint    NOT NULL,
    pending_expiry_minutes_before_start integer     NOT NULL,
    created_by                          uuid        CONSTRAINT fk_policy_author REFERENCES app_users (id) ON DELETE SET NULL,
    created_at                          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_manual_only CHECK (confirmation_mode = 'MANUAL'),
    CONSTRAINT ck_policy_lead        CHECK (min_lead_minutes BETWEEN 0 AND 10080),
    CONSTRAINT ck_policy_advance     CHECK (max_advance_days BETWEEN 1 AND 365),
    CONSTRAINT ck_policy_party       CHECK (max_party_size_web BETWEEN 1 AND 50),
    CONSTRAINT ck_policy_expiry      CHECK (pending_expiry_minutes_before_start BETWEEN 0 AND 1440)
);

CREATE TABLE booking_exceptions (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    service_date      date         NOT NULL,
    service_window_id uuid         CONSTRAINT fk_exception_window REFERENCES service_windows (id) ON DELETE CASCADE,
    kind              text         NOT NULL,
    capacity_override integer,
    reason            varchar(140),
    created_by        uuid         CONSTRAINT fk_exception_author REFERENCES app_users (id) ON DELETE SET NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_exception_kind     CHECK (kind IN ('CLOSED', 'CAPACITY_OVERRIDE')),
    CONSTRAINT ck_exception_override CHECK ((kind = 'CAPACITY_OVERRIDE') = (capacity_override IS NOT NULL)
                                            AND (capacity_override IS NULL OR capacity_override >= 0)),
    CONSTRAINT ux_exception_day_window UNIQUE NULLS NOT DISTINCT (service_date, service_window_id)
);

CREATE TABLE events (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug             varchar(80)  NOT NULL CONSTRAINT ux_event_slug UNIQUE,
    title            varchar(120) NOT NULL,
    kind             text         NOT NULL,
    status           text         NOT NULL DEFAULT 'DRAFT',
    starts_at        timestamptz,
    ends_at          timestamptz,
    zone_id          uuid         CONSTRAINT fk_event_zone REFERENCES capacity_zones (id) ON DELETE RESTRICT,
    exclusive        boolean      NOT NULL DEFAULT true,
    booking_mode     text         NOT NULL DEFAULT 'NONE',
    capacity_covers  integer,
    confirmed_covers integer      NOT NULL DEFAULT 0,
    artist           varchar(120),
    summary          varchar(300),
    body             text,
    poster_media_id  uuid         CONSTRAINT fk_event_poster REFERENCES media_assets (id) ON DELETE RESTRICT,
    created_by       uuid         CONSTRAINT fk_event_author REFERENCES app_users (id) ON DELETE SET NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_event_slug           CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_event_kind           CHECK (kind IN ('SERATA', 'FORMAT')),
    CONSTRAINT ck_event_status         CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_event_booking_mode   CHECK (booking_mode IN ('NONE', 'REQUEST')),
    CONSTRAINT ck_event_time_order     CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_event_aligned        CHECK ((starts_at IS NULL OR extract(epoch FROM starts_at) % 900 = 0)
                                              AND (ends_at IS NULL OR extract(epoch FROM ends_at) % 900 = 0)),
    CONSTRAINT ck_event_published_date CHECK (status <> 'PUBLISHED' OR kind = 'FORMAT' OR starts_at IS NOT NULL),
    CONSTRAINT ck_event_booking_complete CHECK (booking_mode = 'NONE'
                                              OR (starts_at IS NOT NULL AND ends_at IS NOT NULL AND capacity_covers IS NOT NULL)),
    CONSTRAINT ck_event_capacity       CHECK (capacity_covers IS NULL OR capacity_covers >= 0),
    CONSTRAINT ck_event_confirmed      CHECK (confirmed_covers >= 0),
    CONSTRAINT ck_event_quota          CHECK (capacity_covers IS NULL OR confirmed_covers <= capacity_covers)
);
CREATE INDEX ix_events_public ON events (status, starts_at);
CREATE INDEX ix_events_range  ON events USING gist (tstzrange(starts_at, ends_at)) WHERE starts_at IS NOT NULL;

CREATE TABLE event_media (
    event_id   uuid    NOT NULL CONSTRAINT fk_event_media_event REFERENCES events (id) ON DELETE CASCADE,
    media_id   uuid    NOT NULL CONSTRAINT fk_event_media_media REFERENCES media_assets (id) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, media_id)
);

-- Registro di occupazione: per zona e quanto da 15 minuti, capienza effettiva e persone confermate presenti.
-- Le righe si creano solo per i quanti toccati da conferme o eventi esclusivi.
CREATE TABLE capacity_slots (
    zone_id            uuid        NOT NULL CONSTRAINT fk_slot_zone REFERENCES capacity_zones (id) ON DELETE RESTRICT,
    slot_start         timestamptz NOT NULL,
    capacity_covers    integer     NOT NULL,
    occupied_covers    integer     NOT NULL DEFAULT 0,
    exclusive_event_id uuid        CONSTRAINT fk_slot_event REFERENCES events (id) ON DELETE RESTRICT,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (zone_id, slot_start),
    CONSTRAINT ck_slot_aligned         CHECK (extract(epoch FROM slot_start) % 900 = 0),
    CONSTRAINT ck_slot_capacity        CHECK (capacity_covers >= 0),
    CONSTRAINT ck_slot_occupied        CHECK (occupied_covers >= 0),
    CONSTRAINT ck_slot_not_overbooked  CHECK (occupied_covers <= capacity_covers)
);
CREATE INDEX ix_slots_event ON capacity_slots (exclusive_event_id) WHERE exclusive_event_id IS NOT NULL;
