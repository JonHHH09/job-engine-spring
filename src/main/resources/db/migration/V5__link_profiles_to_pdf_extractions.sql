CREATE TABLE IF NOT EXISTS profile.profile_pdf_sources (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    pdf_extraction_id uuid NOT NULL,
    source_type text NOT NULL DEFAULT 'resume_pdf',
    created_at timestamptz NOT NULL,
    CONSTRAINT profile_pdf_sources_profile_id_fk FOREIGN KEY (profile_id)
        REFERENCES profile.profiles (id) ON DELETE CASCADE,
    CONSTRAINT profile_pdf_sources_pdf_extraction_id_fk FOREIGN KEY (pdf_extraction_id)
        REFERENCES document.pdf_extractions (id) ON DELETE RESTRICT,
    CONSTRAINT profile_pdf_sources_source_type_not_blank CHECK (btrim(source_type) <> '')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_pdf_sources_profile_id_unique
    ON profile.profile_pdf_sources (profile_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_pdf_sources_pdf_extraction_id_unique
    ON profile.profile_pdf_sources (pdf_extraction_id);

CREATE INDEX IF NOT EXISTS idx_profile_pdf_sources_created_at
    ON profile.profile_pdf_sources (created_at DESC, id);
