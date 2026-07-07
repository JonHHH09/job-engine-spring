package org.instruct.jobenginespring.adapter.out.postgres.profile;

/**
 * Logical PostgreSQL schema/table names for the profile aggregate.
 *
 * <p>These constants define the target PostgreSQL naming contract for future outbound repository
 * implementations without creating a runtime adapter yet.
 */
public final class ProfileSchema {

    public static final String SCHEMA = "profile";
    public static final Table PROFILES = new Table("profiles");
    public static final Table PROFILE_CONTACTS = new Table("profile_contacts");
    public static final Table PROFILE_LINKS = new Table("profile_links");
    public static final Table PROFILE_SKILLS = new Table("profile_skills");
    public static final Table PROFILE_LANGUAGES = new Table("profile_languages");
    public static final Table EDUCATION = new Table("education");
    public static final Table EXPERIENCES = new Table("experiences");
    public static final Table PROJECTS = new Table("projects");
    public static final Table PROJECT_TECHNOLOGIES = new Table("project_technologies");
    public static final Table PROFILE_RESUME_DOCUMENTS = new Table("profile_resume_documents");

    private ProfileSchema() {
    }

    public record Table(String name) {
        public Table {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("table name must not be blank");
            }
        }

        public String qualifiedName() {
            return SCHEMA + "." + name;
        }
    }
}
