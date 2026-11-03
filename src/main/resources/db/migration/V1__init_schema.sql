CREATE TABLE IF NOT EXISTS users (id SERIAL PRIMARY KEY, email VARCHAR(255) NOT NULL, "name" VARCHAR(100) NOT NULL, password_hash VARCHAR(255) NOT NULL, "role" VARCHAR(20) NOT NULL, phone_number VARCHAR(20) NULL, phone_number_visible BOOLEAN DEFAULT FALSE NOT NULL, whatsapp_number VARCHAR(20) NULL, whatsapp_visible BOOLEAN DEFAULT FALSE NOT NULL, viber_number VARCHAR(20) NULL, viber_visible BOOLEAN DEFAULT FALSE NOT NULL, messenger_username VARCHAR(50) NULL, messenger_visible BOOLEAN DEFAULT FALSE NOT NULL, failed_login_attempts INT DEFAULT 0 NOT NULL, locked_until TIMESTAMP NULL, is_email_verified BOOLEAN DEFAULT FALSE NOT NULL);
ALTER TABLE users ADD CONSTRAINT users_email_unique UNIQUE (email);
CREATE TABLE IF NOT EXISTS refresh_tokens (id SERIAL PRIMARY KEY, user_id INT NOT NULL, token VARCHAR(64) NOT NULL, family_id VARCHAR(36) NOT NULL, is_revoked BOOLEAN DEFAULT FALSE NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, family_expires_at TIMESTAMP NOT NULL, CONSTRAINT fk_refresh_tokens_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX refresh_tokens_user_id ON refresh_tokens (user_id);
ALTER TABLE refresh_tokens ADD CONSTRAINT refresh_tokens_token_unique UNIQUE (token);
CREATE INDEX refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_family_expires_at ON refresh_tokens (family_expires_at);
CREATE TABLE IF NOT EXISTS email_verification_tokens (id SERIAL PRIMARY KEY, user_id INT NOT NULL, token VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, used BOOLEAN DEFAULT FALSE NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_email_verification_tokens_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX email_verification_tokens_user_id ON email_verification_tokens (user_id);
ALTER TABLE email_verification_tokens ADD CONSTRAINT email_verification_tokens_token_unique UNIQUE (token);
CREATE INDEX email_verification_tokens_expires_at ON email_verification_tokens (expires_at);
CREATE TABLE IF NOT EXISTS password_reset_tokens (id SERIAL PRIMARY KEY, user_id INT NOT NULL, token VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, used BOOLEAN DEFAULT FALSE NOT NULL, used_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_password_reset_tokens_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX password_reset_tokens_user_id ON password_reset_tokens (user_id);
ALTER TABLE password_reset_tokens ADD CONSTRAINT password_reset_tokens_token_unique UNIQUE (token);
CREATE INDEX password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
CREATE TABLE IF NOT EXISTS user_email_outbox (id BIGSERIAL PRIMARY KEY, user_id INT NOT NULL, email_type VARCHAR(20) NOT NULL, token_hash VARCHAR(64) NOT NULL, encrypted_token TEXT NOT NULL, expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL, attempt_count INT DEFAULT 0 NOT NULL, next_attempt_at TIMESTAMP NOT NULL, lease_until TIMESTAMP NULL, lease_token VARCHAR(36) NULL, failed_at TIMESTAMP NULL, last_failure_type VARCHAR(100) NULL, CONSTRAINT fk_user_email_outbox_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX user_email_outbox_user_id ON user_email_outbox (user_id);
ALTER TABLE user_email_outbox ADD CONSTRAINT user_email_outbox_email_type_token_hash_unique UNIQUE (email_type, token_hash);
CREATE INDEX user_email_outbox_failed_at_next_attempt_at_lease_until ON user_email_outbox (failed_at, next_attempt_at, lease_until);
CREATE INDEX user_email_outbox_expires_at ON user_email_outbox (expires_at);
CREATE TABLE IF NOT EXISTS idempotency_keys ("key" VARCHAR(64) PRIMARY KEY, request_fingerprint VARCHAR(64) DEFAULT '' NOT NULL, status_code INT NULL, response_body TEXT DEFAULT '' NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE TABLE IF NOT EXISTS companies (id SERIAL PRIMARY KEY, "name" VARCHAR(100) NOT NULL, invite_code VARCHAR(8) NOT NULL, created_at TIMESTAMP NOT NULL);
ALTER TABLE companies ADD CONSTRAINT companies_invite_code_unique UNIQUE (invite_code);
CREATE TABLE IF NOT EXISTS company_memberships (id SERIAL PRIMARY KEY, company_id INT NOT NULL, user_id INT NOT NULL, "role" VARCHAR(10) NOT NULL, joined_at TIMESTAMP NOT NULL, CONSTRAINT fk_company_memberships_company_id__id FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_company_memberships_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX company_memberships_user_id ON company_memberships (user_id);
ALTER TABLE company_memberships ADD CONSTRAINT company_memberships_company_id_user_id_unique UNIQUE (company_id, user_id);
CREATE TABLE IF NOT EXISTS vehicles (id SERIAL PRIMARY KEY, company_id INT NOT NULL, "label" VARCHAR(100) NOT NULL, license_plate VARCHAR(20) NOT NULL, picture_url TEXT NULL, picture_storage_key VARCHAR(128) NULL, created_at TIMESTAMP NOT NULL, archived_at TIMESTAMP NULL, CONSTRAINT fk_vehicles_company_id__id FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE ON UPDATE RESTRICT);
ALTER TABLE vehicles ADD CONSTRAINT vehicles_company_id_license_plate_unique UNIQUE (company_id, license_plate);
CREATE TABLE IF NOT EXISTS vehicle_assignments (id SERIAL PRIMARY KEY, vehicle_id INT NOT NULL, vendor_user_id INT NOT NULL, started_at TIMESTAMP NOT NULL, ended_at TIMESTAMP NULL, location_sharing_consented_at TIMESTAMP NULL, last_telemetry_at TIMESTAMP NULL, CONSTRAINT fk_vehicle_assignments_vehicle_id__id FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_vehicle_assignments_vendor_user_id__id FOREIGN KEY (vendor_user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX vehicle_assignments_vehicle_id ON vehicle_assignments (vehicle_id);
CREATE INDEX vehicle_assignments_vendor_user_id ON vehicle_assignments (vendor_user_id);
ALTER TABLE vehicle_assignments ADD CONSTRAINT vehicle_assignments_id_vehicle_id_unique UNIQUE (id, vehicle_id);
ALTER TABLE vehicle_assignments ADD CONSTRAINT vehicle_assignments_id_vendor_user_id_unique UNIQUE (id, vendor_user_id);
CREATE UNIQUE INDEX vehicle_assignments_vendor_user_id_unique ON vehicle_assignments (vendor_user_id) WHERE vehicle_assignments.ended_at IS NULL;
CREATE UNIQUE INDEX vehicle_assignments_vehicle_id_unique ON vehicle_assignments (vehicle_id) WHERE vehicle_assignments.ended_at IS NULL;
CREATE INDEX vehicle_assignments_ended_at ON vehicle_assignments (ended_at) WHERE vehicle_assignments.ended_at IS NOT NULL;
CREATE TABLE IF NOT EXISTS vehicle_locations (id BIGSERIAL PRIMARY KEY, vehicle_id INT NOT NULL, vendor_user_id INT NULL, assignment_id INT NULL, latitude DECIMAL(9, 6) NOT NULL, longitude DECIMAL(9, 6) NOT NULL, recorded_at TIMESTAMP NOT NULL, cell_id VARCHAR(16) NOT NULL, idempotency_key VARCHAR(36) NOT NULL, CONSTRAINT fk_vehicle_locations_vehicle_id__id FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_vehicle_locations_vendor_user_id__id FOREIGN KEY (vendor_user_id) REFERENCES users(id) ON UPDATE RESTRICT, CONSTRAINT fk_vehicle_locations_assignment_id_vehicle_id__id_vehicle_id FOREIGN KEY (assignment_id, vehicle_id) REFERENCES vehicle_assignments(id, vehicle_id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_vehicle_locations_assignment_id_vendor_user_id__id_vendor_us FOREIGN KEY (assignment_id, vendor_user_id) REFERENCES vehicle_assignments(id, vendor_user_id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT vehicle_locations_latitude_range_check CHECK ((latitude >= -90.0) AND (latitude <= 90.0)), CONSTRAINT vehicle_locations_longitude_range_check CHECK ((longitude >= -180.0) AND (longitude <= 180.0)), CONSTRAINT vehicle_locations_assignment_vendor_required_check CHECK ((assignment_id IS NULL) OR (vendor_user_id IS NOT NULL)));
CREATE INDEX vehicle_locations_assignment_id ON vehicle_locations (assignment_id);
ALTER TABLE vehicle_locations ADD CONSTRAINT vehicle_locations_idempotency_key_unique UNIQUE (idempotency_key);
CREATE TABLE IF NOT EXISTS company_favorites (id SERIAL PRIMARY KEY, user_id INT NOT NULL, company_id INT NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_company_favorites_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_company_favorites_company_id__id FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX company_favorites_company_id ON company_favorites (company_id);
ALTER TABLE company_favorites ADD CONSTRAINT company_favorites_user_id_company_id_unique UNIQUE (user_id, company_id);
CREATE TABLE IF NOT EXISTS user_saved_locations (id SERIAL PRIMARY KEY, user_id INT NOT NULL, "label" VARCHAR(100) NOT NULL, latitude DECIMAL(9, 6) NOT NULL, longitude DECIMAL(9, 6) NOT NULL, radius_km DECIMAL(5, 2) NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_user_saved_locations_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT user_saved_locations_latitude_range_check CHECK ((latitude >= -90.0) AND (latitude <= 90.0)), CONSTRAINT user_saved_locations_longitude_range_check CHECK ((longitude >= -180.0) AND (longitude <= 180.0)), CONSTRAINT user_saved_locations_radius_range_check CHECK ((radius_km >= 0.1) AND (radius_km <= 50.0)));
CREATE INDEX user_saved_locations_user_id ON user_saved_locations (user_id);
ALTER TABLE user_saved_locations ADD CONSTRAINT user_saved_locations_id_user_id_unique UNIQUE (id, user_id);
CREATE TABLE IF NOT EXISTS vehicle_pings (vehicle_id INT, buyer_user_id INT, sent_at TIMESTAMP NOT NULL, CONSTRAINT pk_vehicle_pings PRIMARY KEY (vehicle_id, buyer_user_id), CONSTRAINT fk_vehicle_pings_vehicle_id__id FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_vehicle_pings_buyer_user_id__id FOREIGN KEY (buyer_user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX vehicle_pings_buyer_user_id ON vehicle_pings (buyer_user_id);
CREATE INDEX vehicle_pings_sent_at ON vehicle_pings (sent_at);
CREATE TABLE IF NOT EXISTS device_installations (id SERIAL PRIMARY KEY, user_id INT NOT NULL, installation_id VARCHAR(4096) NOT NULL, installation_id_hash VARCHAR(64) NOT NULL, platform VARCHAR(10) NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, registration_version BIGINT DEFAULT 1 NOT NULL, CONSTRAINT fk_device_installations_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT device_installations_registration_version_positive_check CHECK (registration_version >= 1));
CREATE INDEX device_installations_user_id ON device_installations (user_id);
ALTER TABLE device_installations ADD CONSTRAINT device_installations_installation_id_hash_unique UNIQUE (installation_id_hash);
CREATE TABLE IF NOT EXISTS proximity_notifications (id SERIAL PRIMARY KEY, buyer_user_id INT NOT NULL, saved_location_id INT NOT NULL, vehicle_id INT NOT NULL, currently_inside BOOLEAN NOT NULL, last_attempted_at TIMESTAMP NOT NULL, CONSTRAINT fk_proximity_notifications_buyer_user_id__id FOREIGN KEY (buyer_user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_proximity_notifications_vehicle_id__id FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE ON UPDATE RESTRICT, CONSTRAINT fk_proximity_notifications_saved_location_id_buyer_user_id__id_ FOREIGN KEY (saved_location_id, buyer_user_id) REFERENCES user_saved_locations(id, user_id) ON DELETE CASCADE ON UPDATE RESTRICT);
ALTER TABLE proximity_notifications ADD CONSTRAINT proximity_notifications_buyer_user_id_saved_location_id_vehicle UNIQUE (buyer_user_id, saved_location_id, vehicle_id);
CREATE INDEX proximity_notifications_saved_location_id ON proximity_notifications (saved_location_id);
CREATE INDEX proximity_notifications_vehicle_id ON proximity_notifications (vehicle_id);
CREATE TABLE IF NOT EXISTS sync_events (id BIGSERIAL PRIMARY KEY, user_id INT NOT NULL, entity_type VARCHAR(30) NOT NULL, entity_id INT NOT NULL, operation VARCHAR(10) NOT NULL, occurred_at TIMESTAMP NOT NULL, CONSTRAINT fk_sync_events_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT);
CREATE INDEX sync_events_user_id_id ON sync_events (user_id, id);
CREATE SEQUENCE IF NOT EXISTS users_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS refresh_tokens_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS email_verification_tokens_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS password_reset_tokens_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS user_email_outbox_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS companies_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS company_memberships_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS vehicles_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS vehicle_assignments_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS vehicle_locations_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS company_favorites_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS user_saved_locations_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS device_installations_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS proximity_notifications_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
CREATE SEQUENCE IF NOT EXISTS sync_events_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;

CREATE OR REPLACE FUNCTION prevent_last_company_admin_user_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM company.id
    FROM companies AS company
    INNER JOIN company_memberships AS membership ON membership.company_id = company.id
    WHERE membership.user_id = OLD.id
      AND membership.role = 'ADMIN'
    ORDER BY company.id
    FOR UPDATE OF company;

    IF EXISTS (
        SELECT 1
        FROM company_memberships AS current_admin
        WHERE current_admin.user_id = OLD.id
          AND current_admin.role = 'ADMIN'
          AND NOT EXISTS (
              SELECT 1
              FROM company_memberships AS replacement_admin
              WHERE replacement_admin.company_id = current_admin.company_id
                AND replacement_admin.role = 'ADMIN'
                AND replacement_admin.user_id <> OLD.id
          )
    ) THEN
        RAISE EXCEPTION
            USING ERRCODE = '23514',
                  CONSTRAINT = 'users_last_company_admin_delete',
                  MESSAGE = 'Cannot delete a user who is the last company admin; promote another member first';
    END IF;

    RETURN OLD;
END;
$$;
CREATE TRIGGER users_last_company_admin_delete
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION prevent_last_company_admin_user_delete();

CREATE OR REPLACE FUNCTION cleanup_vehicle_locations_for_user_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM vehicle_locations
    WHERE vendor_user_id = OLD.id
      AND assignment_id IS NOT NULL;

    UPDATE vehicle_locations
    SET vendor_user_id = NULL
    WHERE vendor_user_id = OLD.id
      AND assignment_id IS NULL;

    RETURN OLD;
END;
$$;
CREATE TRIGGER users_vehicle_locations_cleanup
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE FUNCTION cleanup_vehicle_locations_for_user_delete();
