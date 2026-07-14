CREATE TABLE job_schema.search_terms (
    job_id uuid NOT NULL REFERENCES job_schema.jobs(id) ON DELETE CASCADE,
    field_key text NOT NULL,
    term text NOT NULL,
    weight smallint NOT NULL CHECK (weight > 0),
    PRIMARY KEY (job_id, field_key, term)
);

CREATE INDEX job_search_terms_term_prefix_idx
    ON job_schema.search_terms (term COLLATE "C", job_id, field_key) INCLUDE (weight);

CREATE TABLE profile.search_terms (
    profile_id uuid NOT NULL REFERENCES profile.profiles(id) ON DELETE CASCADE,
    field_key text NOT NULL,
    term text NOT NULL,
    weight smallint NOT NULL CHECK (weight > 0),
    PRIMARY KEY (profile_id, field_key, term)
);

CREATE INDEX profile_search_terms_term_prefix_idx
    ON profile.search_terms (term COLLATE "C", profile_id, field_key) INCLUDE (weight);

CREATE INDEX jobs_list_created_id_idx ON job_schema.jobs (created_at DESC, id);
CREATE INDEX profiles_list_created_id_idx ON profile.profiles (created_at DESC, id);
CREATE INDEX reports_list_created_id_idx ON match.reports (created_at DESC, id DESC);
CREATE INDEX reports_profile_created_id_idx ON match.reports (profile_id, created_at DESC, id DESC);
CREATE INDEX reports_job_created_id_idx ON match.reports (job_id, created_at DESC, id DESC);
CREATE INDEX reports_profile_job_created_id_idx
    ON match.reports ((ARRAY[profile_id, job_id]), created_at DESC, id DESC);
