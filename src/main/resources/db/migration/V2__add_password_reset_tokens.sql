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
