CREATE SCHEMA IF NOT EXISTS profile; -- schema for profile data

CREATE TABLE IF NOT EXISTS profile.profiles (  -- profile data
    id uuid PRIMARY KEY, -- profile id
    full_name text NOT NULL, -- full name
    email text NOT NULL, -- email address
    avatar_url text, -- profile picture url
    summary text, -- profile summary
    created_at timestamptz NOT NULL, -- when the profile was created
    updated_at timestamptz NOT NULL, -- when the profile was last updated
    CONSTRAINT profiles_email_unique UNIQUE (email), -- email address must be unique
    CONSTRAINT profiles_email_not_blank CHECK (btrim(email) <> ''), -- email address must not be blank
    CONSTRAINT profiles_full_name_not_blank CHECK (btrim(full_name) <> ''), -- full name must not be blank
    CONSTRAINT profiles_updated_at_not_before_created_at CHECK (updated_at >= created_at) -- updated_at must be after created_at
);

CREATE TABLE IF NOT EXISTS profile.profile_contacts ( -- profile contact data
    id uuid PRIMARY KEY, -- contact id
    profile_id uuid NOT NULL, -- profile id
    contact_type text NOT NULL, -- contact type
    contact_value text NOT NULL, -- contact value
    label text, -- contact label
    created_at timestamptz NOT NULL, -- when the contact was created
    updated_at timestamptz NOT NULL, -- when the contact was last updated
    CONSTRAINT profile_contacts_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT profile_contacts_type_value_unique UNIQUE (profile_id, contact_type, contact_value), -- contact type and value must be unique
    CONSTRAINT profile_contacts_type_not_blank CHECK (btrim(contact_type) <> ''), -- contact type must not be blank
    CONSTRAINT profile_contacts_value_not_blank CHECK (btrim(contact_value) <> ''), -- contact value must not be blank
    CONSTRAINT profile_contacts_updated_at_not_before_created_at CHECK (updated_at >= created_at) -- updated_at must be after created_at
);

CREATE TABLE IF NOT EXISTS profile.profile_links ( -- profile link data
    id uuid PRIMARY KEY, -- link id
    profile_id uuid NOT NULL, -- profile id
    link_type text NOT NULL, -- link type
    url text NOT NULL, -- link url
    label text, -- link label
    created_at timestamptz NOT NULL, -- when the link was created
    updated_at timestamptz NOT NULL, -- when the link was last updated
    CONSTRAINT profile_links_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT profile_links_type_url_unique UNIQUE (profile_id, link_type, url), -- link type and url must be unique
    CONSTRAINT profile_links_type_not_blank CHECK (btrim(link_type) <> ''), -- link type must not be blank
    CONSTRAINT profile_links_url_not_blank CHECK (btrim(url) <> ''), -- link url must not be blank
    CONSTRAINT profile_links_updated_at_not_before_created_at CHECK (updated_at >= created_at) -- updated_at must be after created_at
);

CREATE TABLE IF NOT EXISTS profile.profile_skills ( -- profile skill data
    id uuid PRIMARY KEY, -- skill id
    profile_id uuid NOT NULL, -- profile id
    skill text NOT NULL, -- skill name
    normalized_skill text NOT NULL, -- normalized skill name
    category text, -- skill category
    proficiency text, -- skill proficiency level
    display_order integer NOT NULL DEFAULT 0, -- display order
    created_at timestamptz NOT NULL, -- when the skill was created
    CONSTRAINT profile_skills_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT profile_skills_profile_normalized_unique UNIQUE (profile_id, normalized_skill), -- normalized skill name must be unique
    CONSTRAINT profile_skills_skill_not_blank CHECK (btrim(skill) <> ''), -- skill name must not be blank
    CONSTRAINT profile_skills_normalized_skill_not_blank CHECK (btrim(normalized_skill) <> ''), -- normalized skill name must not be blank
    CONSTRAINT profile_skills_display_order_non_negative CHECK (display_order >= 0) -- display order must be non-negative
);

CREATE TABLE IF NOT EXISTS profile.profile_languages ( -- profile language data
    id uuid PRIMARY KEY, -- language id
    profile_id uuid NOT NULL, -- profile id
    language text NOT NULL, -- language name
    normalized_language text NOT NULL,-- normalized language name
    proficiency text, -- language proficiency level
    display_order integer NOT NULL DEFAULT 0, -- display order
    created_at timestamptz NOT NULL, -- when the language was created
    CONSTRAINT profile_languages_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT profile_languages_profile_normalized_unique UNIQUE (profile_id, normalized_language), -- normalized language name must be unique
    CONSTRAINT profile_languages_language_not_blank CHECK (btrim(language) <> ''), -- language name must not be blank
    CONSTRAINT profile_languages_normalized_language_not_blank CHECK (btrim(normalized_language) <> ''), -- normalized language name must not be blank
    CONSTRAINT profile_languages_display_order_non_negative CHECK (display_order >= 0) -- display order must be non-negative
);

CREATE TABLE IF NOT EXISTS profile.education ( -- profile education data
    id uuid PRIMARY KEY, -- education id
    profile_id uuid NOT NULL, -- profile id
    institution text, -- institution name
    degree text, -- degree name
    field text, -- field of study
    location text, -- location of study
    start_date date, -- start date of study
    end_date date, -- end date of study
    description text, -- description of study
    relevant_focus text,-- relevant focus of study
    created_at timestamptz NOT NULL, -- when the education was created
    CONSTRAINT education_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT education_end_date_not_before_start_date CHECK ( -- end date must be after start date
        start_date IS NULL OR end_date IS NULL OR end_date >= start_date -- if start date is not null, end date must be null or after start date
    )
);

CREATE TABLE IF NOT EXISTS profile.experiences ( -- profile experience data
    id uuid PRIMARY KEY, -- experience id
    profile_id uuid NOT NULL, -- profile id
    company text, -- company name
    title text, -- job title
    location text, -- location of work
    start_date date, -- start date of work
    end_date date, -- end date of work
    description text, -- description of work
    display_order integer NOT NULL DEFAULT 0, -- display order
    created_at timestamptz NOT NULL, -- when the experience was created
    CONSTRAINT experiences_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT experiences_display_order_non_negative CHECK (display_order >= 0), -- display order must be non-negative
    CONSTRAINT experiences_end_date_not_before_start_date CHECK ( -- end date must be after start date
        start_date IS NULL OR end_date IS NULL OR end_date >= start_date -- if start date is not null, end date must be null or after start date
    )
);

CREATE TABLE IF NOT EXISTS profile.projects ( -- profile project data
    id uuid PRIMARY KEY, -- project id
    profile_id uuid NOT NULL, -- profile id
    name text, -- project name
    start_date date, -- start date of project
    end_date date, -- end date of project
    url text, -- project url
    description text, -- project description
    display_order integer NOT NULL DEFAULT 0, -- display order
    created_at timestamptz NOT NULL, -- when the project was created
    CONSTRAINT projects_profile_id_fk FOREIGN KEY (profile_id) -- profile id must exist
        REFERENCES profile.profiles (id) ON DELETE CASCADE, -- profile id must not be deleted
    CONSTRAINT projects_display_order_non_negative CHECK (display_order >= 0) -- display order must be non-negative
);

CREATE TABLE IF NOT EXISTS profile.project_technologies ( -- profile project technology data
    id uuid PRIMARY KEY, -- project technology id
    project_id uuid NOT NULL, -- project id
    technology text NOT NULL, -- technology name
    normalized_technology text NOT NULL, -- normalized technology name
    display_order integer NOT NULL DEFAULT 0, -- display order
    created_at timestamptz NOT NULL, -- when the project technology was created
    CONSTRAINT project_technologies_project_id_fk FOREIGN KEY (project_id) -- project id must exist
        REFERENCES profile.projects (id) ON DELETE CASCADE, -- project id must not be deleted
    CONSTRAINT project_technologies_project_normalized_unique UNIQUE (project_id, normalized_technology), -- normalized technology name must be unique
    CONSTRAINT project_technologies_technology_not_blank CHECK (btrim(technology) <> ''), -- technology name must not be blank
    CONSTRAINT project_technologies_normalized_technology_not_blank CHECK (btrim(normalized_technology) <> ''), -- normalized technology name must not be blank
    CONSTRAINT project_technologies_display_order_non_negative CHECK (display_order >= 0) -- display order must be non-negative
);

CREATE INDEX IF NOT EXISTS idx_profiles_full_name -- index for full name
    ON profile.profiles (full_name); -- full name must be unique

CREATE INDEX IF NOT EXISTS idx_profile_contacts_profile_id -- index for profile id
    ON profile.profile_contacts (profile_id); -- profile id must be unique

CREATE INDEX IF NOT EXISTS idx_profile_contacts_type -- index for contact type
    ON profile.profile_contacts (contact_type); -- contact type must be unique

CREATE INDEX IF NOT EXISTS idx_profile_links_profile_id -- index for profile id
    ON profile.profile_links (profile_id); -- profile id must be unique

CREATE INDEX IF NOT EXISTS idx_profile_links_type -- index for link type
    ON profile.profile_links (link_type); -- link type must be unique

CREATE INDEX IF NOT EXISTS idx_profile_skills_normalized
    ON profile.profile_skills (normalized_skill);

CREATE INDEX IF NOT EXISTS idx_profile_skills_profile_order
    ON profile.profile_skills (profile_id, display_order);

CREATE INDEX IF NOT EXISTS idx_profile_languages_normalized
    ON profile.profile_languages (normalized_language);

CREATE INDEX IF NOT EXISTS idx_profile_languages_profile_order
    ON profile.profile_languages (profile_id, display_order);

CREATE INDEX IF NOT EXISTS idx_education_profile_id
    ON profile.education (profile_id);

CREATE INDEX IF NOT EXISTS idx_experiences_profile_order
    ON profile.experiences (profile_id, display_order);

CREATE INDEX IF NOT EXISTS idx_experiences_company
    ON profile.experiences (company);

CREATE INDEX IF NOT EXISTS idx_projects_profile_order
    ON profile.projects (profile_id, display_order);

CREATE INDEX IF NOT EXISTS idx_project_technologies_normalized
    ON profile.project_technologies (normalized_technology);

CREATE INDEX IF NOT EXISTS idx_project_technologies_project_order
    ON profile.project_technologies (project_id, display_order);
