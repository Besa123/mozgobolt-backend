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
