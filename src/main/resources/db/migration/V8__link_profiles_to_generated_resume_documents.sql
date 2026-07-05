CREATE TABLE IF NOT EXISTS profile.profile_resume_documents (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    document_id uuid NOT NULL,
    file_path text NOT NULL,
    resume_type text NOT NULL DEFAULT 'master_resume',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT profile_resume_documents_profile_id_fk FOREIGN KEY (profile_id)
        REFERENCES profile.profiles (id) ON DELETE CASCADE,
    CONSTRAINT profile_resume_documents_document_id_fk FOREIGN KEY (document_id)
        REFERENCES document.documents (id) ON DELETE RESTRICT,
    CONSTRAINT profile_resume_documents_file_path_not_blank CHECK (btrim(file_path) <> ''),
    CONSTRAINT profile_resume_documents_resume_type_not_blank CHECK (btrim(resume_type) <> ''),
    CONSTRAINT profile_resume_documents_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_resume_documents_profile_id_unique
    ON profile.profile_resume_documents (profile_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_resume_documents_document_id_unique
    ON profile.profile_resume_documents (document_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_resume_documents_file_path_unique
    ON profile.profile_resume_documents (file_path);

CREATE INDEX IF NOT EXISTS idx_profile_resume_documents_updated_at
    ON profile.profile_resume_documents (updated_at DESC, id);
