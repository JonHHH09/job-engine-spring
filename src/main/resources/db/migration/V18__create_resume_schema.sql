CREATE SCHEMA IF NOT EXISTS resume;

CREATE TABLE IF NOT EXISTS resume.resumes (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL,
    job_id uuid NOT NULL,
    format text NOT NULL,
    profile_revision timestamptz NOT NULL,
    job_revision timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT resumes_profile_id_fk FOREIGN KEY (profile_id)
        REFERENCES profile.profiles (id) ON DELETE CASCADE,
    CONSTRAINT resumes_job_id_fk FOREIGN KEY (job_id)
        REFERENCES job_schema.jobs (id) ON DELETE CASCADE,
    CONSTRAINT resumes_format_known CHECK (format IN ('germany')),
    CONSTRAINT resumes_format_not_blank CHECK (btrim(format) <> ''),
    CONSTRAINT resumes_updated_at_not_before_created_at CHECK (updated_at >= created_at),
    CONSTRAINT resumes_profile_job_format_unique UNIQUE (profile_id, job_id, format)
);

CREATE TABLE IF NOT EXISTS resume.resume_variants (
    id uuid PRIMARY KEY,
    resume_id uuid NOT NULL,
    language text NOT NULL,
    document_id uuid NOT NULL,
    file_path text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT resume_variants_resume_id_fk FOREIGN KEY (resume_id)
        REFERENCES resume.resumes (id) ON DELETE CASCADE,
    CONSTRAINT resume_variants_document_id_fk FOREIGN KEY (document_id)
        REFERENCES document.documents (id) ON DELETE RESTRICT,
    CONSTRAINT resume_variants_language_known CHECK (language IN ('en', 'de')),
    CONSTRAINT resume_variants_file_path_not_blank CHECK (btrim(file_path) <> ''),
    CONSTRAINT resume_variants_updated_at_not_before_created_at CHECK (updated_at >= created_at),
    CONSTRAINT resume_variants_resume_language_unique UNIQUE (resume_id, language),
    CONSTRAINT resume_variants_document_id_unique UNIQUE (document_id)
);

CREATE TABLE IF NOT EXISTS resume.resume_sections (
    id uuid PRIMARY KEY,
    variant_id uuid NOT NULL,
    section_type text NOT NULL,
    title text NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    CONSTRAINT resume_sections_variant_id_fk FOREIGN KEY (variant_id)
        REFERENCES resume.resume_variants (id) ON DELETE CASCADE,
    CONSTRAINT resume_sections_section_type_not_blank CHECK (btrim(section_type) <> ''),
    CONSTRAINT resume_sections_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT resume_sections_display_order_non_negative CHECK (display_order >= 0)
);

CREATE TABLE IF NOT EXISTS resume.resume_entries (
    id uuid PRIMARY KEY,
    section_id uuid NOT NULL,
    entry_type text NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    title text,
    organization text,
    location text,
    start_date date,
    end_date date,
    metadata text,
    CONSTRAINT resume_entries_section_id_fk FOREIGN KEY (section_id)
        REFERENCES resume.resume_sections (id) ON DELETE CASCADE,
    CONSTRAINT resume_entries_entry_type_not_blank CHECK (btrim(entry_type) <> ''),
    CONSTRAINT resume_entries_display_order_non_negative CHECK (display_order >= 0)
);

CREATE TABLE IF NOT EXISTS resume.resume_entry_bullets (
    id uuid PRIMARY KEY,
    entry_id uuid NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    text text NOT NULL,
    CONSTRAINT resume_entry_bullets_entry_id_fk FOREIGN KEY (entry_id)
        REFERENCES resume.resume_entries (id) ON DELETE CASCADE,
    CONSTRAINT resume_entry_bullets_text_not_blank CHECK (btrim(text) <> ''),
    CONSTRAINT resume_entry_bullets_display_order_non_negative CHECK (display_order >= 0)
);

CREATE INDEX IF NOT EXISTS idx_resumes_profile_id ON resume.resumes (profile_id);
CREATE INDEX IF NOT EXISTS idx_resumes_job_id ON resume.resumes (job_id);
CREATE INDEX IF NOT EXISTS idx_resumes_updated_at ON resume.resumes (updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_resume_variants_resume_id ON resume.resume_variants (resume_id);
CREATE INDEX IF NOT EXISTS idx_resume_sections_variant_order ON resume.resume_sections (variant_id, display_order);
CREATE INDEX IF NOT EXISTS idx_resume_entries_section_order ON resume.resume_entries (section_id, display_order);
CREATE INDEX IF NOT EXISTS idx_resume_entry_bullets_entry_order ON resume.resume_entry_bullets (entry_id, display_order);
