CREATE TABLE document.generated_resume_file_cleanups (
    id UUID PRIMARY KEY,
    file_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_failure_type VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_generated_resume_file_cleanups_due
    ON document.generated_resume_file_cleanups (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
