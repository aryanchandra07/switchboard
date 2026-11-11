-- V2: AI provider API keys, stored encrypted (FR-04).
-- The plain key is never stored. encrypted_key + nonce can only be decrypted with the master key,
-- which lives in an environment variable, never in the database.

CREATE TABLE provider_keys (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider      VARCHAR(32)  NOT NULL,                -- e.g. 'anthropic'
    label         VARCHAR(100),                         -- admin's note, e.g. 'Team key, $50 limit'
    encrypted_key BYTEA        NOT NULL,                -- AES-256-GCM ciphertext + auth tag
    nonce         BYTEA        NOT NULL,                -- 12 random bytes, unique per encryption
    key_hint      VARCHAR(4)   NOT NULL,                -- last 4 characters, so the admin can tell keys apart
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    rotated_at    TIMESTAMPTZ,                          -- set when the key is replaced

    CONSTRAINT provider_keys_nonce_length CHECK (octet_length(nonce) = 12)
);

-- One active key per provider; saving again replaces (rotates) it.
CREATE UNIQUE INDEX provider_keys_provider_key ON provider_keys (provider);
