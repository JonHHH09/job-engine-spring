CREATE TABLE IF NOT EXISTS document.blobs (
    id uuid PRIMARY KEY,
    sha256 text NOT NULL,
    byte_size bigint NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT blobs_byte_size_positive CHECK (byte_size > 0),
    CONSTRAINT blobs_sha256_not_blank CHECK (btrim(sha256) <> ''),
    CONSTRAINT blobs_sha256_hex CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_document_blobs_sha256_unique
    ON document.blobs (sha256);

CREATE INDEX IF NOT EXISTS idx_document_blobs_created_at
    ON document.blobs (created_at DESC, id);

INSERT INTO document.blobs (id, sha256, byte_size, content, created_at)
SELECT DISTINCT ON (legacy.sha256)
    legacy.id,
    legacy.sha256,
    legacy.byte_size,
    legacy.content,
    legacy.created_at
FROM document.files legacy
ORDER BY legacy.sha256, legacy.created_at, legacy.id
ON CONFLICT (sha256) DO NOTHING;

CREATE TABLE IF NOT EXISTS document.documents (
    id uuid PRIMARY KEY,
    blob_id uuid NOT NULL,
    original_file_name text NOT NULL,
    media_type text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT documents_blob_id_fk FOREIGN KEY (blob_id)
        REFERENCES document.blobs (id) ON DELETE RESTRICT,
    CONSTRAINT documents_original_file_name_not_blank CHECK (btrim(original_file_name) <> ''),
    CONSTRAINT documents_media_type_not_blank CHECK (btrim(media_type) <> ''),
    CONSTRAINT documents_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_document_documents_blob_id
    ON document.documents (blob_id);

CREATE INDEX IF NOT EXISTS idx_document_documents_created_at
    ON document.documents (created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_document_documents_media_type
    ON document.documents (media_type);

INSERT INTO document.documents (id, blob_id, original_file_name, media_type, created_at, updated_at)
SELECT
    legacy.id,
    blob.id,
    legacy.original_file_name,
    legacy.media_type,
    legacy.created_at,
    legacy.updated_at
FROM document.files legacy
JOIN document.blobs blob ON blob.sha256 = legacy.sha256
ON CONFLICT (id) DO NOTHING;

ALTER TABLE document.pdf_extractions
    DROP CONSTRAINT IF EXISTS pdf_extractions_file_id_fk;

ALTER TABLE document.pdf_extractions
    ADD CONSTRAINT pdf_extractions_file_id_fk FOREIGN KEY (file_id)
        REFERENCES document.documents (id) ON DELETE CASCADE;
