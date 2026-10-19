-- FireFlies Aperitif — V3: menu, prezzi, allergeni UE.
-- Riferimento: DATA_MODEL.md §3.6. Prezzi in centesimi; NULL = prezzo non ancora comunicato dal locale.

CREATE TABLE menu_categories (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       varchar(40) NOT NULL CONSTRAINT ux_menu_category_slug UNIQUE,
    name       varchar(40) NOT NULL,
    hint       varchar(80),
    sort_order integer     NOT NULL DEFAULT 0,
    visible    boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_menu_category_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE menu_items (
    id                    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id           uuid         NOT NULL CONSTRAINT fk_item_category REFERENCES menu_categories (id) ON DELETE RESTRICT,
    name                  varchar(80)  NOT NULL,
    description           varchar(400),
    ingredients           varchar(400),
    price_cents           integer,
    photo_media_id        uuid         CONSTRAINT fk_item_photo REFERENCES media_assets (id) ON DELETE RESTRICT,
    allergens_status      text         NOT NULL DEFAULT 'UNDECLARED',
    allergens_verified_at timestamptz,
    allergens_verified_by uuid         CONSTRAINT fk_item_allergens_verifier REFERENCES app_users (id) ON DELETE SET NULL,
    visible               boolean      NOT NULL DEFAULT true,
    sort_order            integer      NOT NULL DEFAULT 0,
    version               integer      NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_item_price             CHECK (price_cents IS NULL OR price_cents BETWEEN 0 AND 100000),
    CONSTRAINT ck_item_allergens_status  CHECK (allergens_status IN ('UNDECLARED', 'DECLARED')),
    CONSTRAINT ck_item_allergens_verified CHECK (allergens_status = 'UNDECLARED' OR allergens_verified_at IS NOT NULL)
);
CREATE INDEX ix_menu_items_category ON menu_items (category_id, sort_order);

-- 14 allergeni del Reg. UE 1169/2011, Allegato II (dato normativo, non demo).
CREATE TABLE allergens (
    code       varchar(20) PRIMARY KEY,
    name_it    varchar(60) NOT NULL,
    sort_order smallint    NOT NULL
);
INSERT INTO allergens (code, name_it, sort_order) VALUES
    ('GLUTEN',      'Glutine',         1),
    ('CRUSTACEANS', 'Crostacei',       2),
    ('EGGS',        'Uova',            3),
    ('FISH',        'Pesce',           4),
    ('PEANUTS',     'Arachidi',        5),
    ('SOYBEANS',    'Soia',            6),
    ('MILK',        'Latte',           7),
    ('NUTS',        'Frutta a guscio', 8),
    ('CELERY',      'Sedano',          9),
    ('MUSTARD',     'Senape',         10),
    ('SESAME',      'Sesamo',         11),
    ('SULPHITES',   'Solfiti',        12),
    ('LUPIN',       'Lupini',         13),
    ('MOLLUSCS',    'Molluschi',      14);

CREATE TABLE menu_item_allergens (
    menu_item_id  uuid        NOT NULL CONSTRAINT fk_item_allergen_item REFERENCES menu_items (id) ON DELETE CASCADE,
    allergen_code varchar(20) NOT NULL CONSTRAINT fk_item_allergen_code REFERENCES allergens (code) ON DELETE RESTRICT,
    PRIMARY KEY (menu_item_id, allergen_code)
);
