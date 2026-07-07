CREATE SCHEMA IF NOT EXISTS job_schema;

CREATE TABLE IF NOT EXISTS job_schema.jobs (
    id uuid PRIMARY KEY,
    source_method text NOT NULL,
    source_label text,
    title text NOT NULL,
    company text,
    location text,
    description text NOT NULL,
    experience_requirement text,
    employment_type text,
    seniority text,
    posted_at timestamptz,
    canonical_fingerprint text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT jobs_source_method_known CHECK (source_method IN ('link', 'text')),
    CONSTRAINT jobs_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT jobs_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT jobs_fingerprint_not_blank CHECK (btrim(canonical_fingerprint) <> ''),
    CONSTRAINT jobs_canonical_fingerprint_unique UNIQUE (canonical_fingerprint),
    CONSTRAINT jobs_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE TABLE IF NOT EXISTS job_schema.job_skills (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL,
    skill text NOT NULL,
    normalized_skill text NOT NULL,
    required boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    CONSTRAINT job_skills_job_id_fk FOREIGN KEY (job_id)
        REFERENCES job_schema.jobs (id) ON DELETE CASCADE,
    CONSTRAINT job_skills_skill_not_blank CHECK (btrim(skill) <> ''),
    CONSTRAINT job_skills_normalized_skill_not_blank CHECK (btrim(normalized_skill) <> ''),
    CONSTRAINT job_skills_display_order_non_negative CHECK (display_order >= 0),
    CONSTRAINT job_skills_job_normalized_unique UNIQUE (job_id, normalized_skill)
);

CREATE TABLE IF NOT EXISTS job_schema.job_link_ingestions (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE,
    url text NOT NULL,
    normalized_url text NOT NULL,
    fetched_at timestamptz NOT NULL,
    http_status integer,
    source_title text,
    created_at timestamptz NOT NULL,
    CONSTRAINT job_link_ingestions_job_id_fk FOREIGN KEY (job_id)
        REFERENCES job_schema.jobs (id) ON DELETE CASCADE,
    CONSTRAINT job_link_ingestions_url_not_blank CHECK (btrim(url) <> ''),
    CONSTRAINT job_link_ingestions_normalized_url_not_blank CHECK (btrim(normalized_url) <> ''),
    CONSTRAINT job_link_ingestions_status_valid CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT job_link_ingestions_normalized_url_unique UNIQUE (normalized_url)
);

CREATE TABLE IF NOT EXISTS job_schema.job_text_ingestions (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL UNIQUE,
    source_label text,
    input_text_hash text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT job_text_ingestions_job_id_fk FOREIGN KEY (job_id)
        REFERENCES job_schema.jobs (id) ON DELETE CASCADE,
    CONSTRAINT job_text_ingestions_hash_not_blank CHECK (btrim(input_text_hash) <> ''),
    CONSTRAINT job_text_ingestions_hash_unique UNIQUE (input_text_hash)
);

CREATE INDEX IF NOT EXISTS idx_jobs_title ON job_schema.jobs (title);
CREATE INDEX IF NOT EXISTS idx_jobs_company ON job_schema.jobs (company);
CREATE INDEX IF NOT EXISTS idx_jobs_source_method ON job_schema.jobs (source_method);
CREATE INDEX IF NOT EXISTS idx_jobs_posted_at ON job_schema.jobs (posted_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_job_skills_normalized ON job_schema.job_skills (normalized_skill);
CREATE INDEX IF NOT EXISTS idx_job_skills_job_order ON job_schema.job_skills (job_id, display_order);
CREATE INDEX IF NOT EXISTS idx_job_link_ingestions_job_id ON job_schema.job_link_ingestions (job_id);
CREATE INDEX IF NOT EXISTS idx_job_text_ingestions_job_id ON job_schema.job_text_ingestions (job_id);
