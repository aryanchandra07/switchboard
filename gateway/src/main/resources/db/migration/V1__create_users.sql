-- V1: user accounts (FR-01, FR-02, FR-03).
-- Flyway runs each V<n>__*.sql file once, in order, and records it in flyway_schema_history.
-- Never edit a migration after it has been applied; add a new V<n+1> file instead.

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,                 -- bcrypt hash, never the plain password
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT users_role_check   CHECK (role   IN ('ADMIN', 'MEMBER')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT users_email_lowercase CHECK (email = lower(email))
);

-- One account per email address (emails are stored lower-case).
CREATE UNIQUE INDEX users_email_key ON users (email);
