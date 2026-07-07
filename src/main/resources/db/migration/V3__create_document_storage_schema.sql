CREATE SCHEMA IF NOT EXISTS document;

CREATE TABLE IF NOT EXISTS document.files (
    id uuid PRIMARY KEY,
    original_file_name text NOT NULL,
    media_type text NOT NULL,
    byte_size bigint NOT NULL,
    sha256 text NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT files_original_file_name_not_blank CHECK (btrim(original_file_name) <> ''),
    CONSTRAINT files_media_type_not_blank CHECK (btrim(media_type) <> ''),
    CONSTRAINT files_byte_size_positive CHECK (byte_size > 0),
    CONSTRAINT files_sha256_not_blank CHECK (btrim(sha256) <> ''),
    CONSTRAINT files_sha256_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT files_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_document_files_sha256_unique
    ON document.files (sha256);

CREATE INDEX IF NOT EXISTS idx_document_files_created_at
    ON document.files (created_at DESC, id);

CREATE TABLE IF NOT EXISTS document.pdf_extractions (
    id uuid PRIMARY KEY,
    file_id uuid NOT NULL,
    extractor text NOT NULL,
    character_count integer NOT NULL,
    page_count integer NOT NULL,
    truncated boolean NOT NULL,
    extracted_text text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pdf_extractions_file_id_fk FOREIGN KEY (file_id)
        REFERENCES document.files (id) ON DELETE CASCADE,
    CONSTRAINT pdf_extractions_extractor_not_blank CHECK (btrim(extractor) <> ''),
    CONSTRAINT pdf_extractions_character_count_non_negative CHECK (character_count >= 0),
    CONSTRAINT pdf_extractions_page_count_positive CHECK (page_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_pdf_extractions_file_created_at
    ON document.pdf_extractions (file_id, created_at DESC, id);
