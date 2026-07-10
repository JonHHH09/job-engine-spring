DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'document'
          AND table_name = 'files'
    ) THEN
        IF EXISTS (
            SELECT 1
            FROM document.files legacy
            LEFT JOIN document.documents stored_document ON stored_document.id = legacy.id
            LEFT JOIN document.blobs blob ON blob.id = stored_document.blob_id
            WHERE stored_document.id IS NULL
               OR blob.id IS NULL
               OR stored_document.original_file_name IS DISTINCT FROM legacy.original_file_name
               OR stored_document.media_type IS DISTINCT FROM legacy.media_type
               OR stored_document.created_at IS DISTINCT FROM legacy.created_at
               OR stored_document.updated_at IS DISTINCT FROM legacy.updated_at
               OR blob.sha256 IS DISTINCT FROM legacy.sha256
               OR blob.byte_size IS DISTINCT FROM legacy.byte_size
               OR blob.content IS DISTINCT FROM legacy.content
        ) THEN
            RAISE EXCEPTION 'document.files backfill verification failed; refusing to drop legacy document data';
        END IF;

        DROP TABLE document.files;
    END IF;
END
$$;
