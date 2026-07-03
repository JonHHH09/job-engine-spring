DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT file_id
        FROM document.pdf_extractions
        GROUP BY file_id
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        DELETE FROM document.pdf_extractions older
        USING document.pdf_extractions newer
        WHERE older.file_id = newer.file_id
          AND (
              older.created_at < newer.created_at
              OR (older.created_at = newer.created_at AND older.id < newer.id)
          );
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_pdf_extractions_file_id_unique
    ON document.pdf_extractions (file_id);
