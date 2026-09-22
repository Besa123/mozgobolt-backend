-- Consolidated initial schema. This app has never been deployed, so there is no live database
-- with migration history to preserve — squashing everything into one file avoids a growing trail
-- of pre-release migrations for no benefit. The next real migration is V2.

CREATE TABLE IF NOT EXISTS users
(
    id                     SERIAL PRIMARY KEY,
    email                  VARCHAR(255) NOT NULL,
    name                   VARCHAR(100) NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    role                   VARCHAR(20)  NOT NULL,
    -- Optional: a vendor may not provide one. PII, handled with the same care as passwords/
    -- tokens/emails elsewhere in this codebase — never logged, only ever exposed to a buyer as
    -- part of a vehicle's driver info while that vehicle has an active assignment.
    phone_number           VARCHAR(20),
    phone_number_visible   BOOLEAN      NOT NULL DEFAULT FALSE,
    whatsapp_number        VARCHAR(20),
    whatsapp_visible       BOOLEAN      NOT NULL DEFAULT FALSE,
    viber_number           VARCHAR(20),
    viber_visible          BOOLEAN      NOT NULL DEFAULT FALSE,
    messenger_username     VARCHAR(50),
    messenger_visible      BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           TIMESTAMP,
    is_email_verified      BOOLEAN      NOT NULL DEFAULT FALSE
);

ALTER TABLE users
    ADD CONSTRAINT users_email_unique UNIQUE (email);

CREATE TABLE IF NOT EXISTS refresh_tokens
(
    id         SERIAL PRIMARY KEY,
    user_id    INT         NOT NULL,
    token      VARCHAR(64) NOT NULL,
    family_id  VARCHAR(36) NOT NULL,
    is_revoked BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_refresh_tokens_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id ON refresh_tokens (family_id);

CREATE TABLE IF NOT EXISTS email_verification_tokens
(
    id         SERIAL PRIMARY KEY,
    user_id    INT         NOT NULL,
    token      VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_email_verification_tokens_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX email_verification_tokens_user_id ON email_verification_tokens (user_id);
ALTER TABLE email_verification_tokens
    ADD CONSTRAINT email_verification_tokens_token_unique UNIQUE (token);

CREATE TABLE IF NOT EXISTS password_reset_tokens
(
    id         SERIAL PRIMARY KEY,
    user_id    INT         NOT NULL,
    token      VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX password_reset_tokens_user_id ON password_reset_tokens (user_id);
ALTER TABLE password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_unique UNIQUE (token);

CREATE TABLE IF NOT EXISTS idempotency_keys
(
    key                  VARCHAR(64) PRIMARY KEY,
    request_fingerprint  VARCHAR(64) NOT NULL DEFAULT '',
    status_code          INT,
    response_body        TEXT        NOT NULL DEFAULT '',
    created_at           TIMESTAMP   NOT NULL
);

-- A vendor's first registration creates a company; other vendors join it later via invite_code.
-- Buyers never have a company. Membership (who belongs, and whether they're an admin) lives in
-- company_memberships below — a user can be an ADMIN of many companies and a MEMBER of others,
-- simultaneously; there is no separate "owner" tier above admin.
CREATE TABLE IF NOT EXISTS companies
(
    id             SERIAL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    -- 8 = CompanyConstraints.INVITE_CODE_LENGTH (base64url of INVITE_CODE_BYTE_LENGTH=6 bytes,
    -- no padding); static SQL can't reference that constant, so if it ever changes, this must
    -- be bumped by hand too.
    invite_code    VARCHAR(8)   NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);

ALTER TABLE companies
    ADD CONSTRAINT companies_invite_code_unique UNIQUE (invite_code);

CREATE TABLE IF NOT EXISTS company_memberships
(
    id         SERIAL PRIMARY KEY,
    company_id INT         NOT NULL,
    user_id    INT         NOT NULL,
    -- 10 = CompanyConstraints.ROLE_COLUMN_LENGTH; static SQL can't reference that constant, so
    -- keep this in sync by hand if it ever changes.
    role       VARCHAR(10) NOT NULL,
    joined_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_company_memberships_company_id__id FOREIGN KEY (company_id)
        REFERENCES companies (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_company_memberships_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

-- Also serves as the lookup index for "all memberships of company X" (company_id is the leading
-- column) — no separate index needed for that.
ALTER TABLE company_memberships
    ADD CONSTRAINT company_memberships_company_id_user_id_unique UNIQUE (company_id, user_id);

CREATE INDEX company_memberships_user_id ON company_memberships (user_id);

CREATE TABLE IF NOT EXISTS vehicles
(
    id            SERIAL PRIMARY KEY,
    company_id    INT          NOT NULL,
    label         VARCHAR(100) NOT NULL,
    license_plate VARCHAR(20)  NOT NULL,
    picture_url   TEXT,
    created_at    TIMESTAMP    NOT NULL,
    -- NULL = active (the default), non-null = archived (soft-deleted) at that instant. A vehicle
    -- is archived, never hard-deleted, so its driving history in vehicle_locations/
    -- vehicle_assignments survives — the same reasoning that already governs that history's
    -- 30-day retention window.
    archived_at   TIMESTAMP,
    CONSTRAINT fk_vehicles_company_id__id FOREIGN KEY (company_id)
        REFERENCES companies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX vehicles_company_id ON vehicles (company_id);

-- Scoped to (company_id, license_plate), not globally unique — this app deliberately isn't
-- solving nationwide/cross-border license plate uniqueness, matching the project's existing
-- stance against over-engineering for scale that doesn't exist yet.
ALTER TABLE vehicles
    ADD CONSTRAINT vehicles_company_id_license_plate_unique UNIQUE (company_id, license_plate);

-- A vendor links himself to a vehicle for a shift and delinks at day's end; all GPS telemetry is
-- tied to whichever vehicle currently has an active (ended_at IS NULL) assignment for that vendor.
-- The two partial unique indexes enforce "at most one active assignment per vendor" and "at most
-- one active assignment per vehicle" at the DB level — partial indexes aren't representable in
-- Exposed's DSL, so this lives only here.
CREATE TABLE IF NOT EXISTS vehicle_assignments
(
    id             SERIAL PRIMARY KEY,
    vehicle_id     INT       NOT NULL,
    vendor_user_id INT       NOT NULL,
    started_at     TIMESTAMP NOT NULL,
    ended_at       TIMESTAMP,
    CONSTRAINT fk_vehicle_assignments_vehicle_id__id FOREIGN KEY (vehicle_id)
        REFERENCES vehicles (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_vehicle_assignments_vendor_user_id__id FOREIGN KEY (vendor_user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX vehicle_assignments_vehicle_id ON vehicle_assignments (vehicle_id);
CREATE INDEX vehicle_assignments_vendor_user_id ON vehicle_assignments (vendor_user_id);
CREATE UNIQUE INDEX vehicle_assignments_one_active_per_vendor ON vehicle_assignments (vendor_user_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX vehicle_assignments_one_active_per_vehicle ON vehicle_assignments (vehicle_id) WHERE ended_at IS NULL;

-- Durable slow-path store for GPS telemetry. High-frequency and append-only (BIGSERIAL, like
-- sync_events below) — deliberately NOT part of the sync_events outbox: that mechanism is for a
-- user's own low-frequency CRUD mutations to sync across their own devices, not for broadcasting
-- high-frequency location pings to other users. Real-time delivery to buyers happens entirely via
-- the in-memory fast-path hub (feature/vehicleTracking); this table backs recent history only —
-- specifically, payroll/hours verification for a completed driving session — not permanent
-- analytics. Purged automatically 30 days after a session ends (see
-- VehicleLocationRetentionPurger), not kept indefinitely; a future "let a company keep this
-- session's route long-term" opt-out would just need one more condition in that purge query.
CREATE TABLE IF NOT EXISTS vehicle_locations
(
    id             BIGSERIAL PRIMARY KEY,
    vehicle_id     INT           NOT NULL,
    vendor_user_id INT,
    -- Which driving session this point was recorded during — always set in practice (telemetry
    -- ingestion requires an active assignment to succeed at all), but nullable at the DB level
    -- since it's a foreign key, not a correctness invariant enforced here.
    assignment_id  INT,
    latitude       NUMERIC(9, 6) NOT NULL,
    longitude      NUMERIC(9, 6) NOT NULL,
    recorded_at    TIMESTAMP     NOT NULL,
    -- Denormalized from the coordinate at ingestion (same H3 cell the fast-path hub uses), so a
    -- future "what was near this area" query is an indexed lookup, not a recompute over history.
    -- 16 = H3CellIndexer.CELL_ADDRESS_MAX_LENGTH (H3 addresses are hex-encoded 64-bit indexes);
    -- static SQL can't reference that constant, so keep this in sync by hand if it ever changes.
    cell_id        VARCHAR(16)   NOT NULL,
    CONSTRAINT fk_vehicle_locations_vehicle_id__id FOREIGN KEY (vehicle_id)
        REFERENCES vehicles (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_vehicle_locations_vendor_user_id__id FOREIGN KEY (vendor_user_id)
        REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    -- CASCADE, not SET NULL like vendor_user_id above: once a session's own record is gone, its
    -- location history has no independent reason to survive. This also makes account deletion a
    -- real erasure of a user's location history (not just an anonymization) for any session tied
    -- to them, since deleting a user already cascades their vehicle_assignments.
    CONSTRAINT fk_vehicle_locations_assignment_id__id FOREIGN KEY (assignment_id)
        REFERENCES vehicle_assignments (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX vehicle_locations_vehicle_id_recorded_at ON vehicle_locations (vehicle_id, recorded_at);
CREATE INDEX vehicle_locations_cell_id_recorded_at ON vehicle_locations (cell_id, recorded_at);
-- Supports the retention purge's lookup of which rows belong to a now-stale session, plus the
-- defensive assignment_id IS NULL fallback path.
CREATE INDEX vehicle_locations_assignment_id ON vehicle_locations (assignment_id);

-- Buyer-side, company-level favorites (not per-vehicle — a buyer cares about "any FamilyFrost
-- vehicle nearby", not one specific vehicle's identity).
CREATE TABLE IF NOT EXISTS company_favorites
(
    id         SERIAL PRIMARY KEY,
    user_id    INT       NOT NULL,
    company_id INT       NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_company_favorites_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_company_favorites_company_id__id FOREIGN KEY (company_id)
        REFERENCES companies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

ALTER TABLE company_favorites
    ADD CONSTRAINT company_favorites_user_id_company_id_unique UNIQUE (user_id, company_id);

-- Supports "which buyers favorited company X" (the notification-matching query below) with
-- company_id as the leading column.
CREATE INDEX company_favorites_company_id ON company_favorites (company_id);

-- The geofence source for the "notify me when a favorited company's vehicle is nearby" push
-- feature (feature/proximityNotification below).
CREATE TABLE IF NOT EXISTS user_saved_locations
(
    id         SERIAL PRIMARY KEY,
    user_id    INT           NOT NULL,
    label      VARCHAR(100)  NOT NULL,
    latitude   NUMERIC(9, 6) NOT NULL,
    longitude  NUMERIC(9, 6) NOT NULL,
    radius_km  NUMERIC(5, 2) NOT NULL,
    created_at TIMESTAMP     NOT NULL,
    CONSTRAINT fk_user_saved_locations_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX user_saved_locations_user_id ON user_saved_locations (user_id);

-- Buyer -> vehicle "I'd like to buy something" signal, delivered live over SSE to whichever
-- vendor currently holds the vehicle's active assignment. Append-only and high-frequency-shaped
-- (BIGSERIAL, like vehicle_locations above), not a per-user CRUD entity, so it is deliberately
-- NOT part of the sync_events outbox.
CREATE TABLE IF NOT EXISTS vehicle_pings
(
    id            BIGSERIAL PRIMARY KEY,
    vehicle_id    INT       NOT NULL,
    buyer_user_id INT       NOT NULL,
    sent_at       TIMESTAMP NOT NULL,
    CONSTRAINT fk_vehicle_pings_vehicle_id__id FOREIGN KEY (vehicle_id)
        REFERENCES vehicles (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_vehicle_pings_buyer_user_id__id FOREIGN KEY (buyer_user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

-- Supports the cooldown check: "most recent ping from this buyer to this vehicle" is an indexed
-- lookup, (vehicle_id, buyer_user_id) leading so it matches the WHERE clause, sent_at trailing so
-- the ORDER BY ... LIMIT 1 for that pair is satisfied straight from the index.
CREATE INDEX vehicle_pings_vehicle_id_buyer_user_id_sent_at
    ON vehicle_pings (vehicle_id, buyer_user_id, sent_at);

-- Firebase Installation ID (FID) registration, so a push notification can reach a user's phone
-- even when they have no live SSE connection open (the "app isn't open" fallback for
-- vehicle_pings and the proximity notifications below). A user can register several devices at
-- once. This project targets FCM's newer FID model (Message.Builder#setFid), not the older
-- registration-token API (deprecated by firebase-admin 9.10.0 in favor of fid) — there was no
-- released client yet to migrate, so it started on the current API directly. Installation ids are
-- opaque values issued by Firebase, not credentials a user typed, so they are stored as-is rather
-- than hashed.
CREATE TABLE IF NOT EXISTS device_installations
(
    id              SERIAL PRIMARY KEY,
    user_id         INT           NOT NULL,
    -- 4096: generous headroom over a Firebase Installation ID's real-world length, not a measured
    -- exact maximum (Firebase does not publish one) — see
    -- feature/deviceInstallation/domain/model/DeviceInstallationConstraints.kt.
    installation_id VARCHAR(4096) NOT NULL,
    -- 10: DeviceInstallationConstraints.PLATFORM_COLUMN_LENGTH (longest DevicePlatform name is
    -- "ANDROID", 7 chars, with headroom the same way CompanyConstraints.ROLE_COLUMN_LENGTH is).
    platform        VARCHAR(10)   NOT NULL,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT fk_device_installations_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX device_installations_user_id ON device_installations (user_id);

-- Re-registering the same installation id for the same user (e.g. app restart) must not create a
-- duplicate row — it should just refresh updated_at.
ALTER TABLE device_installations
    ADD CONSTRAINT device_installations_user_id_installation_id_unique UNIQUE (user_id, installation_id);

-- Debounce state for the "notify a buyer when a favorited company's vehicle enters one of their
-- saved locations" push feature (feature/proximityNotification).
--
-- One row per (buyer, saved location, vehicle) triple that has ever been evaluated as "inside" at
-- least once. currently_inside tracks whether the vehicle is inside that saved location's radius
-- as of the last telemetry point evaluated; last_notified_at is when a push was last actually
-- sent for this triple. A push fires again only when the vehicle re-enters after having left
-- (currently_inside flips false -> true), or when enough time has passed while continuously
-- inside — see ProximityAlertServiceI.PROXIMITY_NOTIFICATION_COOLDOWN — so a vehicle parked
-- inside the radius doesn't get pushed on every telemetry point, but a long enough dwell still
-- reminds the buyer once.
CREATE TABLE IF NOT EXISTS proximity_notifications
(
    id                SERIAL PRIMARY KEY,
    buyer_user_id     INT       NOT NULL,
    saved_location_id INT       NOT NULL,
    vehicle_id        INT       NOT NULL,
    currently_inside  BOOLEAN   NOT NULL,
    last_notified_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_proximity_notifications_buyer_user_id__id FOREIGN KEY (buyer_user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_proximity_notifications_saved_location_id__id FOREIGN KEY (saved_location_id)
        REFERENCES user_saved_locations (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_proximity_notifications_vehicle_id__id FOREIGN KEY (vehicle_id)
        REFERENCES vehicles (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

ALTER TABLE proximity_notifications
    ADD CONSTRAINT proximity_notifications_buyer_location_vehicle_unique
        UNIQUE (buyer_user_id, saved_location_id, vehicle_id);

-- The candidate-narrowing queries this feature relies on (favoriting a company, and that user's
-- saved locations) are already backed by company_favorites(company_id) and
-- user_saved_locations(user_id) above — no new index needed for those. This table's own lookup
-- is a straight equality match on all three FK columns of the unique constraint above, which
-- Postgres already indexes to enforce it, so no separate index is added here either.

-- sync_events is the durable outbox for multi-device sync (ADR 0006): every mutation to a synced
-- entity (companies, company_memberships, vehicles, vehicle_assignments, company_favorites,
-- user_saved_locations) inserts one row here in the same transaction as the write itself. Clients
-- pull `WHERE user_id = ? AND id > ?` to catch up cheaply after being offline; a live push channel
-- (Postgres NOTIFY) is a hint to re-pull, never the data path.
--
-- BIGSERIAL (not the SERIAL used elsewhere in this schema): every other table's id space is
-- bounded by how many rows a user actually keeps, but this one is append-only and never pruned
-- in v1 — it should never realistically wrap.
CREATE TABLE IF NOT EXISTS sync_events
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     INT         NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id   INT         NOT NULL,
    operation   VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_sync_events_user_id__id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

-- Supports the exact catch-up query: all of one user's events past a cursor, in order.
CREATE INDEX sync_events_user_id_id ON sync_events (user_id, id);
