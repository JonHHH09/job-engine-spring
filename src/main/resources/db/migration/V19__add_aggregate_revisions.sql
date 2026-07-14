ALTER TABLE profile.profiles
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT profiles_revision_non_negative CHECK (revision >= 0) NOT VALID;

ALTER TABLE job_schema.jobs
    ADD COLUMN revision bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT jobs_revision_non_negative CHECK (revision >= 0) NOT VALID;
