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
) NOT NULL, "name" VARCHAR
(
    100
) NOT NULL, password_hash VARCHAR
(
    255
) NOT NULL);
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
    512
) NOT NULL, is_revoked BOOLEAN DEFAULT FALSE NOT NULL, CONSTRAINT fk_refresh_tokens_user_id__id FOREIGN KEY
(
    user_id
) REFERENCES users
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT);
CREATE INDEX refresh_tokens_user_id ON refresh_tokens (user_id);