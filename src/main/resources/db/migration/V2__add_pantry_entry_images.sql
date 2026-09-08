-- Pictures attached to a pantry entry (up to 3 per entry, enforced in the service layer).
--
-- storage_key is an opaque, server-generated identifier into the configured image storage backend
-- (local disk today) — never derived from client input.
--
-- ON DELETE CASCADE here is a safety net, not the primary cleanup path: PantryEntryServiceI deletes
-- these rows explicitly (via PantryEntryImageCleanup) before deleting the parent entry so it can
-- collect the storage keys and purge the physical files, which this cascade cannot do on its own.
CREATE TABLE IF NOT EXISTS pantry_entry_images
(
    id
    SERIAL
    PRIMARY
    KEY,
    pantry_entry_id
    INT
    NOT
    NULL,
    storage_key
    VARCHAR
(
    255
) NOT NULL,
    content_type VARCHAR
(
    50
) NOT NULL,
    size_bytes INT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_pantry_entry_images_storage_key UNIQUE
(
    storage_key
),
    CONSTRAINT fk_pantry_entry_images_pantry_entry_id__id FOREIGN KEY
(
    pantry_entry_id
)
    REFERENCES pantry_entries
(
    id
) ON DELETE CASCADE
  ON UPDATE RESTRICT
    );

CREATE INDEX pantry_entry_images_pantry_entry_id ON pantry_entry_images (pantry_entry_id);
