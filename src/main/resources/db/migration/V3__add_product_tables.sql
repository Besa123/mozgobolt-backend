CREATE TABLE IF NOT EXISTS storage_locations
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    name
    VARCHAR
(
    100
) NOT NULL,
    CONSTRAINT fk_storage_locations_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX storage_locations_user_id ON storage_locations (user_id);
ALTER TABLE storage_locations
    ADD CONSTRAINT uq_user_location_name UNIQUE (user_id, name);

CREATE TABLE IF NOT EXISTS quantity_units
(
    id
    SERIAL
    PRIMARY
    KEY,
    name
    VARCHAR
(
    20
) NOT NULL,
    category VARCHAR
(
    20
) NOT NULL,
    multiplier NUMERIC
(
    10,
    4
) NOT NULL
    );

ALTER TABLE quantity_units
    ADD CONSTRAINT quantity_units_name_unique UNIQUE (name);

CREATE TABLE IF NOT EXISTS products
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT,
    name
    VARCHAR
(
    100
) NOT NULL,
    default_lifespan_days INT,
    default_unit_category VARCHAR
(
    20
),
    CONSTRAINT fk_products_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX products_user_id ON products (user_id);

-- Case-insensitive dedup for the autocomplete dictionary: one entry per name among global
-- products (user_id IS NULL), one entry per (user, name) among private ones. Partial/functional
-- indexes aren't representable in Exposed's DSL, so this lives only here — see ProductsTable.kt.
CREATE UNIQUE INDEX products_global_name_unique ON products (lower(name)) WHERE user_id IS NULL;
CREATE UNIQUE INDEX products_user_name_unique ON products (user_id, lower(name)) WHERE user_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS pantry_entries
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    product_id
    INT
    NOT
    NULL,
    storage_location_id
    INT,
    unit_id
    INT
    NOT
    NULL,
    quantity_amount
    NUMERIC
(
    10,
    2
) NOT NULL,
    expiration_date DATE,
    brand_or_note VARCHAR
(
    255
),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_pantry_entries_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT,
    CONSTRAINT fk_pantry_entries_product_id__id FOREIGN KEY
(
    product_id
)
    REFERENCES products
(
    id
)
  ON DELETE CASCADE
  ON UPDATE RESTRICT,
    CONSTRAINT fk_pantry_entries_storage_location_id__id FOREIGN KEY
(
    storage_location_id
)
    REFERENCES storage_locations
(
    id
)
  ON DELETE SET NULL
  ON UPDATE RESTRICT,
    CONSTRAINT fk_pantry_entries_unit_id__id FOREIGN KEY
(
    unit_id
)
    REFERENCES quantity_units
(
    id
)
  ON DELETE RESTRICT
  ON UPDATE RESTRICT
    );

CREATE INDEX pantry_entries_user_id ON pantry_entries (user_id);
CREATE INDEX pantry_entries_product_id ON pantry_entries (product_id);
CREATE INDEX pantry_entries_storage_location_id ON pantry_entries (storage_location_id);

-- Seed: Hungarian units. PIECE deliberately has only "db" — see QuantityUnitsTable.kt for why
-- container units (doboz/üveg/csomag) are intentionally not modeled as convertible units.
INSERT INTO quantity_units (name, category, multiplier)
VALUES ('kg', 'MASS', 1000.0000),
       ('dkg', 'MASS', 10.0000),
       ('g', 'MASS', 1.0000),
       ('l', 'VOLUME', 1000.0000),
       ('dl', 'VOLUME', 100.0000),
       ('ml', 'VOLUME', 1.0000),
       ('db', 'PIECE', 1.0000);

-- Seed: a small, illustrative set of global products. Deliberately not exhaustive (YAGNI) —
-- the dictionary is meant to grow mainly through users' own private entries.
INSERT INTO products (name, default_lifespan_days, default_unit_category)
VALUES ('Tej', 7, 'VOLUME'),
       ('Tojás', 21, 'PIECE'),
       ('Sajt', 14, 'MASS'),
       ('Kenyér', 5, 'PIECE'),
       ('Sonka', 5, 'MASS'),
       ('Csirkehús', 3, 'MASS'),
       ('Joghurt', 14, 'MASS'),
       ('Uborka', 10, 'PIECE'),
       ('Paradicsom', 7, 'PIECE'),
       ('Alma', 21, 'PIECE'),
       ('Vöröshagyma', 30, 'PIECE'),
       ('Krumpli', 30, 'MASS'),
       ('Só', NULL, NULL);
