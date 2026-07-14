ALTER TABLE profile.profiles
    VALIDATE CONSTRAINT profiles_revision_non_negative;

ALTER TABLE job_schema.jobs
    VALIDATE CONSTRAINT jobs_revision_non_negative;
