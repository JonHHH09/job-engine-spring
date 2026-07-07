DROP INDEX IF EXISTS profile.idx_profile_resume_documents_profile_id_unique;

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_resume_documents_profile_id_resume_type_unique
    ON profile.profile_resume_documents (profile_id, resume_type);
