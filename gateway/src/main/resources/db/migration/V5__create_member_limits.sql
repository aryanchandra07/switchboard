-- V5: per-member limits set by the admin (FR-06). Enforced by the gateway in later steps.

CREATE TABLE member_limits (
    user_id            UUID          PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    daily_budget_usd   NUMERIC(10,2) NOT NULL,
    monthly_budget_usd NUMERIC(10,2) NOT NULL,
    rpm_limit          INTEGER       NOT NULL,           -- requests per minute
    allowed_models     TEXT[]        NOT NULL,           -- explicit allow-list, e.g. {llama3.2,claude-haiku}
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT member_limits_budgets_check CHECK (daily_budget_usd >= 0 AND monthly_budget_usd >= daily_budget_usd),
    CONSTRAINT member_limits_rpm_check     CHECK (rpm_limit BETWEEN 1 AND 10000)
);
