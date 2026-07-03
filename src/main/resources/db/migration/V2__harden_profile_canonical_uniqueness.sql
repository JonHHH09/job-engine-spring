DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT lower(btrim(email))
        FROM profile.profiles
        GROUP BY lower(btrim(email))
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical profile email group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id, lower(btrim(contact_type)), btrim(contact_value)
        FROM profile.profile_contacts
        GROUP BY profile_id, lower(btrim(contact_type)), btrim(contact_value)
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical profile contact group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id, lower(btrim(link_type)), btrim(url)
        FROM profile.profile_links
        GROUP BY profile_id, lower(btrim(link_type)), btrim(url)
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical profile link group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id, lower(btrim(normalized_skill))
        FROM profile.profile_skills
        GROUP BY profile_id, lower(btrim(normalized_skill))
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical profile skill group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT profile_id, lower(btrim(normalized_language))
        FROM profile.profile_languages
        GROUP BY profile_id, lower(btrim(normalized_language))
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical profile language group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_groups integer;
BEGIN
    SELECT count(*) INTO duplicate_groups
    FROM (
        SELECT project_id, lower(btrim(normalized_technology))
        FROM profile.project_technologies
        GROUP BY project_id, lower(btrim(normalized_technology))
        HAVING count(*) > 1
    ) duplicates;

    IF duplicate_groups > 0 THEN
        RAISE EXCEPTION
            'V2 blocked: % duplicate canonical project technology group(s). Clean/merge rows before applying migration.',
            duplicate_groups;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_profiles_email_canonical_unique
    ON profile.profiles (lower(btrim(email)));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_contacts_type_value_canonical_unique
    ON profile.profile_contacts (profile_id, lower(btrim(contact_type)), btrim(contact_value));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_links_type_url_canonical_unique
    ON profile.profile_links (profile_id, lower(btrim(link_type)), btrim(url));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_skills_normalized_canonical_unique
    ON profile.profile_skills (profile_id, lower(btrim(normalized_skill)));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_languages_normalized_canonical_unique
    ON profile.profile_languages (profile_id, lower(btrim(normalized_language)));

CREATE UNIQUE INDEX IF NOT EXISTS idx_project_technologies_normalized_canonical_unique
    ON profile.project_technologies (project_id, lower(btrim(normalized_technology)));

ALTER TABLE profile.profiles
    ADD CONSTRAINT profiles_email_canonical
        CHECK (email = lower(btrim(email))) NOT VALID;

ALTER TABLE profile.profile_contacts
    ADD CONSTRAINT profile_contacts_type_canonical
        CHECK (contact_type = lower(btrim(contact_type))) NOT VALID,
    ADD CONSTRAINT profile_contacts_value_canonical
        CHECK (contact_value = btrim(contact_value)) NOT VALID,
    ADD CONSTRAINT profile_contacts_label_canonical
        CHECK (label IS NULL OR (label = btrim(label) AND label <> '')) NOT VALID;

ALTER TABLE profile.profile_links
    ADD CONSTRAINT profile_links_type_canonical
        CHECK (link_type = lower(btrim(link_type))) NOT VALID,
    ADD CONSTRAINT profile_links_url_canonical
        CHECK (url = btrim(url)) NOT VALID,
    ADD CONSTRAINT profile_links_label_canonical
        CHECK (label IS NULL OR (label = btrim(label) AND label <> '')) NOT VALID;

ALTER TABLE profile.profile_skills
    ADD CONSTRAINT profile_skills_skill_canonical
        CHECK (skill = btrim(skill)) NOT VALID,
    ADD CONSTRAINT profile_skills_normalized_skill_canonical
        CHECK (normalized_skill = lower(btrim(normalized_skill))) NOT VALID,
    ADD CONSTRAINT profile_skills_category_canonical
        CHECK (category IS NULL OR (category = btrim(category) AND category <> '')) NOT VALID;

ALTER TABLE profile.profile_languages
    ADD CONSTRAINT profile_languages_language_canonical
        CHECK (language = btrim(language)) NOT VALID,
    ADD CONSTRAINT profile_languages_normalized_language_canonical
        CHECK (normalized_language = lower(btrim(normalized_language))) NOT VALID,
    ADD CONSTRAINT profile_languages_proficiency_canonical
        CHECK (proficiency IS NULL OR (proficiency = btrim(proficiency) AND proficiency <> '')) NOT VALID;

ALTER TABLE profile.project_technologies
    ADD CONSTRAINT project_technologies_technology_canonical
        CHECK (technology = btrim(technology)) NOT VALID,
    ADD CONSTRAINT project_technologies_normalized_technology_canonical
        CHECK (normalized_technology = lower(btrim(normalized_technology))) NOT VALID;
