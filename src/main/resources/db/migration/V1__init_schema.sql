-- Consolidated initial schema. This app has never been deployed, so there is no live database
-- with migration history to preserve — squashing what were previously V1-V4 into one file avoids
-- a growing trail of pre-release migrations for no benefit. The next real migration is V2.

CREATE TABLE IF NOT EXISTS users
(
    id
    SERIAL
    PRIMARY
    KEY,
    email
    VARCHAR
(
    255
) NOT NULL,
    name VARCHAR
(
    100
) NOT NULL,
    password_hash VARCHAR
(
    255
) NOT NULL,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    is_email_verified BOOLEAN NOT NULL DEFAULT FALSE
    );

ALTER TABLE users
    ADD CONSTRAINT users_email_unique UNIQUE (email);

CREATE TABLE IF NOT EXISTS refresh_tokens
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    token
    VARCHAR
(
    64
) NOT NULL,
    family_id VARCHAR
(
    36
) NOT NULL,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_refresh_tokens_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id ON refresh_tokens (family_id);

CREATE TABLE IF NOT EXISTS email_verification_tokens
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    token
    VARCHAR
(
    64
) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_email_verification_tokens_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX email_verification_tokens_user_id ON email_verification_tokens (user_id);
ALTER TABLE email_verification_tokens
    ADD CONSTRAINT email_verification_tokens_token_unique UNIQUE (token);

CREATE TABLE IF NOT EXISTS password_reset_tokens
(
    id
    SERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    token
    VARCHAR
(
    64
) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX password_reset_tokens_user_id ON password_reset_tokens (user_id);
ALTER TABLE password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_unique UNIQUE (token);

CREATE TABLE IF NOT EXISTS idempotency_keys
(
    key
    VARCHAR
(
    64
) PRIMARY KEY,
    request_fingerprint VARCHAR
(
    64
) NOT NULL DEFAULT '',
    status_code INT,
    response_body TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL
    );

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

-- sync_events is the durable outbox for multi-device sync (ADR 0006): every mutation to a synced
-- entity (products, storage_locations, pantry_entries) inserts one row here in the same
-- transaction as the write itself. Clients pull `WHERE user_id = ? AND id > ?` to catch up
-- cheaply after being offline; a live push channel (Postgres NOTIFY) is a hint to re-pull, never
-- the data path.
--
-- BIGSERIAL (not the SERIAL used elsewhere in this schema): every other table's id space is
-- bounded by how many rows a user actually keeps, but this one is append-only and never pruned
-- in v1 — it should never realistically wrap.
CREATE TABLE IF NOT EXISTS sync_events
(
    id
    BIGSERIAL
    PRIMARY
    KEY,
    user_id
    INT
    NOT
    NULL,
    entity_type
    VARCHAR
(
    30
) NOT NULL,
    entity_id INT NOT NULL,
    operation VARCHAR
(
    10
) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_sync_events_user_id__id FOREIGN KEY
(
    user_id
)
    REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

-- Supports the exact catch-up query: all of one user's events past a cursor, in order.
CREATE INDEX sync_events_user_id_id ON sync_events (user_id, id);

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
