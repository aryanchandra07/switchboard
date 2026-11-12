-- V4: members' own sb- API keys (FR-05).
-- Only a SHA-256 hash of each key is stored. The full key is shown once, at creation.

CREATE TABLE api_keys (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,  -- removing a member removes their keys
    key_hash     CHAR(64)     NOT NULL,              -- hex SHA-256 of the full key
    key_prefix   VARCHAR(16)  NOT NULL,              -- first characters, e.g. "sb-AbC12345", to recognise a key
    name         VARCHAR(50)  NOT NULL,              -- member's label, e.g. "laptop script"
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,                        -- set when revoked; a revoked key never works again
    last_used_at TIMESTAMPTZ
);

-- Every request looks a key up by its hash, so this must be fast and unique.
CREATE UNIQUE INDEX api_keys_key_hash_key ON api_keys (key_hash);
CREATE INDEX api_keys_user_idx ON api_keys (user_id, created_at DESC);

-- Request logs can now point at the key that was used.
ALTER TABLE requests
    ADD CONSTRAINT requests_api_key_fk FOREIGN KEY (api_key_id) REFERENCES api_keys (id) ON DELETE SET NULL;
