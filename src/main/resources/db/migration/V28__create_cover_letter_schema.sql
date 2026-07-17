CREATE SCHEMA IF NOT EXISTS cover_letter;

CREATE UNIQUE INDEX IF NOT EXISTS resumes_profile_job_id_unique
    ON resume.resumes (profile_id, job_id, id);

CREATE TABLE IF NOT EXISTS cover_letter.cover_letters (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    job_id uuid NOT NULL,
    resume_id uuid NOT NULL,
    profile_revision timestamptz NOT NULL,
    job_revision timestamptz NOT NULL,
    resume_revision timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT cover_letters_profile_id_fk FOREIGN KEY (profile_id)
        REFERENCES profile.profiles (id) ON DELETE CASCADE,
    CONSTRAINT cover_letters_job_id_fk FOREIGN KEY (job_id)
        REFERENCES job_schema.jobs (id) ON DELETE CASCADE,
    CONSTRAINT cover_letters_resume_identity_fk FOREIGN KEY (profile_id, job_id, resume_id)
        REFERENCES resume.resumes (profile_id, job_id, id) ON DELETE CASCADE,
    CONSTRAINT cover_letters_updated_at_not_before_created_at CHECK (updated_at >= created_at),
    CONSTRAINT cover_letters_profile_job_resume_unique UNIQUE (profile_id, job_id, resume_id)
);

CREATE TABLE IF NOT EXISTS cover_letter.cover_letter_variants (
    id uuid PRIMARY KEY,
    cover_letter_id uuid NOT NULL,
    format text NOT NULL,
    language text NOT NULL,
    document_id uuid NOT NULL,
    file_path text NOT NULL,
    subject text NOT NULL,
    salutation text NOT NULL,
    closing text NOT NULL,
    signature text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT cover_letter_variants_cover_letter_id_fk FOREIGN KEY (cover_letter_id)
        REFERENCES cover_letter.cover_letters (id) ON DELETE CASCADE,
    CONSTRAINT cover_letter_variants_document_id_fk FOREIGN KEY (document_id)
        REFERENCES document.documents (id) ON DELETE RESTRICT,
    CONSTRAINT cover_letter_variants_format_known CHECK (format IN ('germany')),
    CONSTRAINT cover_letter_variants_language_known CHECK (language IN ('de')),
    CONSTRAINT cover_letter_variants_file_path_not_blank CHECK (btrim(file_path) <> ''),
    CONSTRAINT cover_letter_variants_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT cover_letter_variants_salutation_not_blank CHECK (btrim(salutation) <> ''),
    CONSTRAINT cover_letter_variants_closing_not_blank CHECK (btrim(closing) <> ''),
    CONSTRAINT cover_letter_variants_signature_not_blank CHECK (btrim(signature) <> ''),
    CONSTRAINT cover_letter_variants_updated_at_not_before_created_at CHECK (updated_at >= created_at),
    CONSTRAINT cover_letter_variants_cover_letter_format_language_unique UNIQUE (cover_letter_id, format, language),
    CONSTRAINT cover_letter_variants_document_id_unique UNIQUE (document_id)
);

CREATE TABLE IF NOT EXISTS cover_letter.cover_letter_paragraphs (
    id uuid PRIMARY KEY,
    variant_id uuid NOT NULL,
    display_order integer NOT NULL,
    text text NOT NULL,
    CONSTRAINT cover_letter_paragraphs_variant_id_fk FOREIGN KEY (variant_id)
        REFERENCES cover_letter.cover_letter_variants (id) ON DELETE CASCADE,
    CONSTRAINT cover_letter_paragraphs_text_not_blank CHECK (btrim(text) <> ''),
    CONSTRAINT cover_letter_paragraphs_display_order_non_negative CHECK (display_order >= 0),
    CONSTRAINT cover_letter_paragraphs_variant_order_unique UNIQUE (variant_id, display_order)
);

CREATE INDEX IF NOT EXISTS idx_cover_letters_profile_id ON cover_letter.cover_letters (profile_id);
CREATE INDEX IF NOT EXISTS idx_cover_letters_job_id ON cover_letter.cover_letters (job_id);
CREATE INDEX IF NOT EXISTS idx_cover_letters_resume_id ON cover_letter.cover_letters (resume_id);
CREATE INDEX IF NOT EXISTS idx_cover_letter_variants_cover_letter_id ON cover_letter.cover_letter_variants (cover_letter_id);
CREATE INDEX IF NOT EXISTS idx_cover_letter_paragraphs_variant_order ON cover_letter.cover_letter_paragraphs (variant_id, display_order);
