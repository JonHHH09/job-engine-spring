-- JOB-64 branch-local migration numbers must be reconciled with parallel migrations before integration.
ALTER TABLE profile.profiles
    VALIDATE CONSTRAINT profiles_revision_non_negative;

ALTER TABLE job_schema.jobs
    VALIDATE CONSTRAINT jobs_revision_non_negative;
