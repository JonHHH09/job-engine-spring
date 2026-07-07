DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id,
               coalesce(lower(btrim(institution)), ''),
               coalesce(lower(btrim(degree)), ''),
               coalesce(lower(btrim(field)), ''),
               coalesce(start_date, DATE '0001-01-01'),
               coalesce(end_date, DATE '9999-12-31')
        FROM profile.education
        GROUP BY profile_id,
                 coalesce(lower(btrim(institution)), ''),
                 coalesce(lower(btrim(degree)), ''),
                 coalesce(lower(btrim(field)), ''),
                 coalesce(start_date, DATE '0001-01-01'),
                 coalesce(end_date, DATE '9999-12-31')
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V6 blocked: % duplicate canonical education group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id,
               coalesce(lower(btrim(company)), ''),
               coalesce(lower(btrim(title)), ''),
               coalesce(start_date, DATE '0001-01-01'),
               coalesce(end_date, DATE '9999-12-31')
        FROM profile.experiences
        GROUP BY profile_id,
                 coalesce(lower(btrim(company)), ''),
                 coalesce(lower(btrim(title)), ''),
                 coalesce(start_date, DATE '0001-01-01'),
                 coalesce(end_date, DATE '9999-12-31')
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V6 blocked: % duplicate canonical experience group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id,
               coalesce(lower(btrim(name)), ''),
               coalesce(btrim(url), '')
        FROM profile.projects
        GROUP BY profile_id,
                 coalesce(lower(btrim(name)), ''),
                 coalesce(btrim(url), '')
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V6 blocked: % duplicate canonical project group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_education_canonical_unique
    ON profile.education (
        profile_id,
        coalesce(lower(btrim(institution)), ''),
        coalesce(lower(btrim(degree)), ''),
        coalesce(lower(btrim(field)), ''),
        coalesce(start_date, DATE '0001-01-01'),
        coalesce(end_date, DATE '9999-12-31')
    );

CREATE UNIQUE INDEX IF NOT EXISTS idx_experiences_canonical_unique
    ON profile.experiences (
        profile_id,
        coalesce(lower(btrim(company)), ''),
        coalesce(lower(btrim(title)), ''),
        coalesce(start_date, DATE '0001-01-01'),
        coalesce(end_date, DATE '9999-12-31')
    );

CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_canonical_unique
    ON profile.projects (
        profile_id,
        coalesce(lower(btrim(name)), ''),
        coalesce(btrim(url), '')
    );

ALTER TABLE profile.education
    ADD CONSTRAINT education_institution_canonical
        CHECK (institution IS NULL OR (institution = btrim(institution) AND institution <> '')) NOT VALID,
    ADD CONSTRAINT education_degree_canonical
        CHECK (degree IS NULL OR (degree = btrim(degree) AND degree <> '')) NOT VALID,
    ADD CONSTRAINT education_field_canonical
        CHECK (field IS NULL OR (field = btrim(field) AND field <> '')) NOT VALID,
    ADD CONSTRAINT education_location_canonical
        CHECK (location IS NULL OR (location = btrim(location) AND location <> '')) NOT VALID,
    ADD CONSTRAINT education_relevant_focus_canonical
        CHECK (relevant_focus IS NULL OR (relevant_focus = btrim(relevant_focus) AND relevant_focus <> '')) NOT VALID;

ALTER TABLE profile.experiences
    ADD CONSTRAINT experiences_company_canonical
        CHECK (company IS NULL OR (company = btrim(company) AND company <> '')) NOT VALID,
    ADD CONSTRAINT experiences_title_canonical
        CHECK (title IS NULL OR (title = btrim(title) AND title <> '')) NOT VALID,
    ADD CONSTRAINT experiences_location_canonical
        CHECK (location IS NULL OR (location = btrim(location) AND location <> '')) NOT VALID,
    ADD CONSTRAINT experiences_description_canonical
        CHECK (description IS NULL OR (description = btrim(description) AND description <> '')) NOT VALID;

ALTER TABLE profile.projects
    ADD CONSTRAINT projects_name_canonical
        CHECK (name IS NULL OR (name = btrim(name) AND name <> '')) NOT VALID,
    ADD CONSTRAINT projects_url_canonical
        CHECK (url IS NULL OR (url = btrim(url) AND url <> '')) NOT VALID,
    ADD CONSTRAINT projects_description_canonical
        CHECK (description IS NULL OR (description = btrim(description) AND description <> '')) NOT VALID;
