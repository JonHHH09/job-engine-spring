CREATE TABLE IF NOT EXISTS profile.profile_personal_details (
    profile_id uuid PRIMARY KEY,
    date_of_birth date,
    nationality text,
    photo_document_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT profile_personal_details_profile_id_fk FOREIGN KEY (profile_id)
        REFERENCES profile.profiles (id) ON DELETE CASCADE,
    CONSTRAINT profile_personal_details_photo_document_id_fk FOREIGN KEY (photo_document_id)
        REFERENCES document.documents (id) ON DELETE SET NULL,
    CONSTRAINT profile_personal_details_nationality_not_blank CHECK (
        nationality IS NULL OR btrim(nationality) <> ''
    ),
    CONSTRAINT profile_personal_details_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE INDEX IF NOT EXISTS idx_profile_personal_details_photo_document_id
    ON profile.profile_personal_details (photo_document_id)
    WHERE photo_document_id IS NOT NULL;
