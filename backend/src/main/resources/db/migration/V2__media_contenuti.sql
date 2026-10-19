-- FireFlies Aperitif — V2: libreria foto e contenuti del sito.
-- Riferimento: DATA_MODEL.md §3.7. Le foto non sono nel database: solo metadati e chiavi di storage.

CREATE TABLE media_assets (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_key       varchar(200) NOT NULL CONSTRAINT ux_media_storage_key UNIQUE,
    status            text         NOT NULL DEFAULT 'PENDING_UPLOAD',
    mime_type         text         NOT NULL,
    bytes             integer      NOT NULL,
    width             integer,
    height            integer,
    sha256            char(64),
    alt_text          varchar(250),
    credit            varchar(120),
    consent_note      varchar(250),
    in_gallery        boolean      NOT NULL DEFAULT false,
    gallery_order     integer,
    uploaded_by       uuid         CONSTRAINT fk_media_uploaded_by REFERENCES app_users (id) ON DELETE SET NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    ready_at          timestamptz,
    upload_expires_at timestamptz,
    CONSTRAINT ck_media_status CHECK (status IN ('PENDING_UPLOAD', 'PROCESSING', 'READY', 'REJECTED')),
    CONSTRAINT ck_media_mime   CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_media_bytes  CHECK (bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_media_size   CHECK ((width IS NULL OR width > 0) AND (height IS NULL OR height > 0)),
    CONSTRAINT ck_media_ready  CHECK (status <> 'READY' OR (alt_text IS NOT NULL AND width IS NOT NULL AND height IS NOT NULL))
);
CREATE INDEX ix_media_gallery ON media_assets (gallery_order) WHERE in_gallery AND status = 'READY';
CREATE INDEX ix_media_pending ON media_assets (upload_expires_at) WHERE status = 'PENDING_UPLOAD';

CREATE TABLE media_variants (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    media_id    uuid         NOT NULL CONSTRAINT fk_variant_media REFERENCES media_assets (id) ON DELETE CASCADE,
    variant     text         NOT NULL,
    storage_key varchar(200) NOT NULL CONSTRAINT ux_variant_storage_key UNIQUE,
    width       integer      NOT NULL CHECK (width > 0),
    height      integer      NOT NULL CHECK (height > 0),
    bytes       integer      NOT NULL CHECK (bytes > 0),
    CONSTRAINT ck_variant_kind CHECK (variant IN ('W480', 'W960', 'W1600')),
    CONSTRAINT ux_variant_per_media UNIQUE (media_id, variant)
);

-- Riga unica: impostazioni pubbliche del locale.
CREATE TABLE site_settings (
    id                     smallint     PRIMARY KEY DEFAULT 1,
    address_line           varchar(160),
    map_query              varchar(200),
    map_mode               text         NOT NULL DEFAULT 'CONSENT',
    phone_public           varchar(20),
    email_public           varchar(254),
    instagram_url          varchar(200),
    address_confirmed      boolean      NOT NULL DEFAULT false,
    hours_confirmed        boolean      NOT NULL DEFAULT false,
    menu_hero_interval_sec integer      NOT NULL DEFAULT 60,
    sms_enabled            boolean      NOT NULL DEFAULT false,
    version                integer      NOT NULL DEFAULT 0,
    updated_by             uuid         CONSTRAINT fk_settings_updated_by REFERENCES app_users (id) ON DELETE SET NULL,
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_settings_map_mode  CHECK (map_mode IN ('CONSENT', 'AUTO')),
    CONSTRAINT ck_settings_interval  CHECK (menu_hero_interval_sec BETWEEN 10 AND 600)
);
COMMENT ON COLUMN site_settings.sms_enabled IS 'Uno dei tre interruttori necessari per un SMS reale (ADR-0005). Default spento.';

-- Riga unica: testi della home e della storia.
CREATE TABLE site_content (
    id              smallint     PRIMARY KEY DEFAULT 1,
    hero_claim      varchar(120),
    hero_lead       varchar(400),
    hero_media_id   uuid         CONSTRAINT fk_content_hero_media REFERENCES media_assets (id) ON DELETE RESTRICT,
    story_title     varchar(120),
    story_text      text,
    story_confirmed boolean      NOT NULL DEFAULT false,
    version         integer      NOT NULL DEFAULT 0,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_singleton CHECK (id = 1)
);

CREATE TABLE site_sections (
    code       varchar(30) PRIMARY KEY,
    enabled    boolean     NOT NULL DEFAULT true,
    sort_order integer     NOT NULL DEFAULT 0
);

-- Orari PUBBLICI (non decidono le prenotazioni).
CREATE TABLE opening_hours (
    id              uuid     PRIMARY KEY DEFAULT gen_random_uuid(),
    iso_day         smallint NOT NULL,
    opens_at        time,
    closes_at       time,
    closes_next_day boolean  NOT NULL DEFAULT false,
    closed          boolean  NOT NULL DEFAULT false,
    CONSTRAINT ck_hours_day   CHECK (iso_day BETWEEN 1 AND 7),
    CONSTRAINT ck_hours_times CHECK (closed OR (opens_at IS NOT NULL AND closes_at IS NOT NULL
                                               AND (closes_next_day OR closes_at > opens_at)))
);
CREATE INDEX ix_hours_day ON opening_hours (iso_day);

CREATE TABLE opening_exceptions (
    id        uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    day       date         NOT NULL CONSTRAINT ux_opening_exception_day UNIQUE,
    closed    boolean      NOT NULL DEFAULT true,
    opens_at  time,
    closes_at time,
    note      varchar(140),
    CONSTRAINT ck_opening_exception_times CHECK (closed OR (opens_at IS NOT NULL AND closes_at IS NOT NULL))
);

CREATE TABLE hero_slides (
    id         uuid     PRIMARY KEY DEFAULT gen_random_uuid(),
    page       text     NOT NULL,
    media_id   uuid     NOT NULL CONSTRAINT fk_slide_media REFERENCES media_assets (id) ON DELETE RESTRICT,
    sort_order integer  NOT NULL,
    enabled    boolean  NOT NULL DEFAULT true,
    CONSTRAINT ck_slide_page CHECK (page IN ('MENU', 'HOME')),
    CONSTRAINT ux_slide_order UNIQUE (page, sort_order) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE owners (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           varchar(80)  NOT NULL,
    role_label     varchar(80),
    description    varchar(600),
    motto          varchar(160),
    front_media_id uuid         CONSTRAINT fk_owner_front_media REFERENCES media_assets (id) ON DELETE RESTRICT,
    back_media_id  uuid         CONSTRAINT fk_owner_back_media  REFERENCES media_assets (id) ON DELETE RESTRICT,
    color_token    varchar(20),
    sort_order     integer      NOT NULL DEFAULT 0,
    visible        boolean      NOT NULL DEFAULT true
);

CREATE TABLE staff_members (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(80) NOT NULL,
    role_label varchar(80),
    media_id   uuid        CONSTRAINT fk_staff_media REFERENCES media_assets (id) ON DELETE RESTRICT,
    sort_order integer     NOT NULL DEFAULT 0,
    visible    boolean     NOT NULL DEFAULT true
);

CREATE TABLE announcements (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    text          varchar(140) NOT NULL,
    tone          text         NOT NULL DEFAULT 'INFO',
    active        boolean      NOT NULL DEFAULT true,
    visible_until timestamptz,
    created_by    uuid         CONSTRAINT fk_announcement_author REFERENCES app_users (id) ON DELETE SET NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcement_tone CHECK (tone IN ('INFO', 'IMPORTANT'))
);
CREATE INDEX ix_announcements_active ON announcements (active, visible_until);
