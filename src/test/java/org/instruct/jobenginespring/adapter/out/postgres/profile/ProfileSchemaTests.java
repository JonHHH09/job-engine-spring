package org.instruct.jobenginespring.adapter.out.postgres.profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileSchemaTests {

    @Test
    void exposesTargetPostgresQualifiedNames() {
        assertEquals("profile", ProfileSchema.SCHEMA);
        assertEquals("profile.profiles", ProfileSchema.PROFILES.qualifiedName());
        assertEquals("profile.profile_contacts", ProfileSchema.PROFILE_CONTACTS.qualifiedName());
        assertEquals("profile.profile_links", ProfileSchema.PROFILE_LINKS.qualifiedName());
        assertEquals("profile.profile_skills", ProfileSchema.PROFILE_SKILLS.qualifiedName());
        assertEquals("profile.profile_languages", ProfileSchema.PROFILE_LANGUAGES.qualifiedName());
        assertEquals("profile.education", ProfileSchema.EDUCATION.qualifiedName());
        assertEquals("profile.experiences", ProfileSchema.EXPERIENCES.qualifiedName());
        assertEquals("profile.projects", ProfileSchema.PROJECTS.qualifiedName());
        assertEquals("profile.project_technologies", ProfileSchema.PROJECT_TECHNOLOGIES.qualifiedName());
    }

    @Test
    void rejectsBlankTableNames() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileSchema.Table(null));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSchema.Table(" "));
    }
}
