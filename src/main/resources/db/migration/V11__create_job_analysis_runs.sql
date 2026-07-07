CREATE TABLE IF NOT EXISTS job_schema.job_analysis_runs (
    id uuid PRIMARY KEY,
    source_type text NOT NULL,
    original_url text,
    normalized_url text,
    fetch_status text NOT NULL,
    http_status integer,
    fetched_title text,
    input_sha256 text NOT NULL,
    input_json jsonb NOT NULL,
    hermes_status text NOT NULL,
    hermes_response_json jsonb,
    hermes_response_sha256 text,
    validation_status text NOT NULL,
    validation_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_job_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT job_analysis_runs_source_type_known CHECK (source_type IN ('link')),
    CONSTRAINT job_analysis_runs_fetch_status_not_blank CHECK (btrim(fetch_status) <> ''),
    CONSTRAINT job_analysis_runs_http_status_valid CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT job_analysis_runs_input_sha256_not_blank CHECK (btrim(input_sha256) <> ''),
    CONSTRAINT job_analysis_runs_hermes_status_not_blank CHECK (btrim(hermes_status) <> ''),
    CONSTRAINT job_analysis_runs_validation_status_not_blank CHECK (btrim(validation_status) <> ''),
    CONSTRAINT job_analysis_runs_created_job_id_fk FOREIGN KEY (created_job_id)
        REFERENCES job_schema.jobs (id) ON DELETE SET NULL,
    CONSTRAINT job_analysis_runs_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_job_analysis_runs_normalized_url
    ON job_schema.job_analysis_runs (normalized_url);

CREATE INDEX IF NOT EXISTS idx_job_analysis_runs_input_sha256
    ON job_schema.job_analysis_runs (input_sha256);

CREATE INDEX IF NOT EXISTS idx_job_analysis_runs_created_job_id
    ON job_schema.job_analysis_runs (created_job_id);

CREATE INDEX IF NOT EXISTS idx_job_analysis_runs_validation_status
    ON job_schema.job_analysis_runs (validation_status);
