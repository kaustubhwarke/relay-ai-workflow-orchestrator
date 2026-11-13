-- Relay schema v1
-- All run/step/approval state lives here so that a killed process loses nothing.

CREATE TABLE workflows (
    id                   VARCHAR(100) PRIMARY KEY,
    name                 VARCHAR(255) NOT NULL,
    description          TEXT,
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('draft', 'published')),
    definition           JSONB        NOT NULL,           -- trigger.secret is stripped; see webhook_secret_hash
    webhook_secret_hash  VARCHAR(64),                     -- hex SHA-256 of the webhook secret (never stored in clear)
    current_version      INTEGER      NOT NULL DEFAULT 0, -- last published version number (0 = never published)
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at         TIMESTAMPTZ
);

-- Immutable, numbered history; every publish appends one row.
CREATE TABLE workflow_versions (
    workflow_id          VARCHAR(100) NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    version              INTEGER      NOT NULL,
    definition           JSONB        NOT NULL,
    webhook_secret_hash  VARCHAR(64),
    published_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_id, version)
);

CREATE TABLE runs (
    id                   VARCHAR(64)  PRIMARY KEY,
    workflow_id          VARCHAR(100) NOT NULL REFERENCES workflows (id),
    workflow_version     INTEGER      NOT NULL,
    definition_snapshot  JSONB        NOT NULL,           -- the definition exactly as published when the run was triggered
    status               VARCHAR(20)  NOT NULL
                         CHECK (status IN ('queued', 'running', 'waiting_approval', 'succeeded', 'failed', 'cancelled')),
    trigger_type         VARCHAR(20)  NOT NULL,
    input                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    current_node_id      VARCHAR(100),
    steps_executed       INTEGER      NOT NULL DEFAULT 0,
    ai_tokens_used       INTEGER      NOT NULL DEFAULT 0,
    error                JSONB,
    cancel_requested     BOOLEAN      NOT NULL DEFAULT FALSE,
    cancelled_reason     VARCHAR(255),
    replay_of_run_id     VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ
);
CREATE INDEX idx_runs_workflow_created ON runs (workflow_id, created_at DESC);
CREATE INDEX idx_runs_status ON runs (status);
CREATE INDEX idx_runs_created ON runs (created_at DESC);

-- The trace. One row per node execution (node ids repeat when a run loops).
CREATE TABLE steps (
    id                   BIGSERIAL    PRIMARY KEY,
    run_id               VARCHAR(64)  NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    sequence             INTEGER      NOT NULL,
    node_id              VARCHAR(100) NOT NULL,
    node_type            VARCHAR(30)  NOT NULL,
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('running', 'waiting', 'succeeded', 'failed', 'skipped')),
    attempt              INTEGER      NOT NULL DEFAULT 1,
    resolved_input       JSONB,
    output               JSONB,
    error                TEXT,
    error_code           VARCHAR(64),
    tokens_prompt        INTEGER,
    tokens_completion    INTEGER,
    ai_attempts          JSONB,                            -- per-attempt AI log (validation errors, tokens)
    idempotency_key      VARCHAR(255),
    approval_id          VARCHAR(64),
    resume_at            TIMESTAMPTZ,                      -- delay nodes: when the run may continue
    started_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at          TIMESTAMPTZ,
    duration_ms          BIGINT,
    UNIQUE (run_id, sequence)                              -- a zombie worker cannot append a duplicate step
);
CREATE INDEX idx_steps_run_sequence ON steps (run_id, sequence);

CREATE TABLE approvals (
    id                   VARCHAR(64)  PRIMARY KEY,
    run_id               VARCHAR(64)  NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    node_id              VARCHAR(100) NOT NULL,
    step_sequence        INTEGER      NOT NULL,
    message              TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_comment     TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (run_id, step_sequence)
);
CREATE INDEX idx_approvals_status ON approvals (status, created_at);
CREATE INDEX idx_approvals_run ON approvals (run_id);

-- Durable work queue: one row per run that has work to do.
-- Claimed with SELECT ... FOR UPDATE SKIP LOCKED; a crashed worker's lease simply expires.
CREATE TABLE queue_jobs (
    id                   BIGSERIAL    PRIMARY KEY,
    run_id               VARCHAR(64)  NOT NULL UNIQUE REFERENCES runs (id) ON DELETE CASCADE,
    available_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by            VARCHAR(100),
    locked_until         TIMESTAMPTZ,
    claim_count          INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_queue_available ON queue_jobs (available_at);

-- Cron-style schedule triggers (1-minute granularity, no catch-up).
CREATE TABLE schedules (
    workflow_id          VARCHAR(100) PRIMARY KEY REFERENCES workflows (id) ON DELETE CASCADE,
    cron                 VARCHAR(100) NOT NULL,
    next_fire_at         TIMESTAMPTZ  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_schedules_due ON schedules (enabled, next_fire_at);
