-- JOB-66 also introduces a parallel migration. Coordinate merge order and renumber this
-- still-unapplied immutable migration if necessary; never edit it after deployment.
UPDATE document.blobs
SET byte_size = octet_length(content)
WHERE byte_size <> octet_length(content);

ALTER TABLE document.blobs
    ADD CONSTRAINT blobs_byte_size_matches_content
        CHECK (byte_size = octet_length(content)) NOT VALID;

ALTER TABLE document.blobs
    VALIDATE CONSTRAINT blobs_byte_size_matches_content;

ALTER TABLE document.pdf_extractions
    ADD COLUMN page_projections jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT pdf_extractions_page_projections_array
        CHECK (jsonb_typeof(page_projections) = 'array'),
    ADD CONSTRAINT pdf_extractions_page_projections_bounded
        CHECK (jsonb_array_length(page_projections) <= 200);

COMMENT ON COLUMN document.pdf_extractions.page_projections IS
    'Bounded canonical page projections used to serve compatible cached extraction views.';
