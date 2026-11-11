-- V3: one row per chat request that reaches the pipeline (FR-21).
-- Only metadata is stored: never the prompt or the reply text.

CREATE TABLE requests (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          REFERENCES users (id) ON DELETE SET NULL,  -- null until member auth on /v1 exists
    api_key_id     UUID,                                                   -- member sb- key used (FK added with api_keys table)
    provider       VARCHAR(32),                                            -- ollama / anthropic / mock
    model          VARCHAR(100),                                           -- model actually used (or requested, if it failed)
    router_score   DOUBLE PRECISION,
    tokens_in      INTEGER       NOT NULL DEFAULT 0,
    tokens_out     INTEGER       NOT NULL DEFAULT 0,
    cost_usd       NUMERIC(12,6) NOT NULL DEFAULT 0,
    latency_ms     INTEGER       NOT NULL DEFAULT 0,
    cache_hit      BOOLEAN       NOT NULL DEFAULT false,
    status         VARCHAR(16)   NOT NULL,                                 -- SUCCESS / ERROR / BLOCKED
    error_code     VARCHAR(64),                                            -- e.g. ollama_unavailable
    blocked_reason VARCHAR(64),                                            -- set by guardrails / limits later
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT requests_status_check CHECK (status IN ('SUCCESS', 'ERROR', 'BLOCKED')),
    CONSTRAINT requests_non_negative CHECK (tokens_in >= 0 AND tokens_out >= 0 AND cost_usd >= 0 AND latency_ms >= 0)
);

-- "My usage today/this month" and "all usage" queries.
CREATE INDEX requests_user_created_idx ON requests (user_id, created_at DESC);
CREATE INDEX requests_created_idx      ON requests (created_at DESC);
