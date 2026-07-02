package org.instruct.jobenginespring.adapter.out.postgres.profile;

import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PostgresProfileRepository implements ProfileRepository {

    private final JdbcOperations jdbc;

    @Override
    public List<UserProfile> listProfiles() {
        return jdbc.query("""
                SELECT id, full_name, email, summary, created_at, updated_at
                FROM profile.profiles
                ORDER BY full_name, id
                """, this::mapProfile);
    }

    @Override
    public Optional<UserProfile> findProfileById(UUID profileId) {
        return jdbc.query("""
                        SELECT id, full_name, email, summary, created_at, updated_at
                        FROM profile.profiles
                        WHERE id = ?
                        """,
                this::mapProfile,
                profileId
        ).stream().findFirst();
    }

    @Override
    public List<ProfileContact> listContacts(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, contact_type, contact_value, label, created_at, updated_at
                        FROM profile.profile_contacts
                        WHERE profile_id = ?
                        ORDER BY contact_type, contact_value, id
                        """,
                this::mapContact,
                profileId
        );
    }

    @Override
    public List<ProfileLink> listLinks(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, link_type, url, label, created_at, updated_at
                        FROM profile.profile_links
                        WHERE profile_id = ?
                        ORDER BY link_type, url, id
                        """,
                this::mapLink,
                profileId
        );
    }

    @Override
    public List<ProfileSkill> listSkills(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, skill, normalized_skill, category, display_order, created_at
                        FROM profile.profile_skills
                        WHERE profile_id = ?
                        ORDER BY display_order, skill, id
                        """,
                this::mapSkill,
                profileId
        );
    }

    @Override
    public List<ProfileLanguage> listLanguages(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, language, normalized_language, proficiency, display_order, created_at
                        FROM profile.profile_languages
                        WHERE profile_id = ?
                        ORDER BY display_order, language, id
                        """,
                this::mapLanguage,
                profileId
        );
    }

    @Override
    public List<Education> listEducation(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, institution, degree, field, location, start_date, end_date,
                               relevant_focus, created_at
                        FROM profile.education
                        WHERE profile_id = ?
                        ORDER BY start_date NULLS LAST, institution, id
                        """,
                this::mapEducation,
                profileId
        );
    }

    @Override
    public List<Experience> listExperiences(UUID profileId) {
        return jdbc.query("""
                        SELECT id, profile_id, company, title, location, start_date, end_date, description,
                               display_order, created_at
                        FROM profile.experiences
                        WHERE profile_id = ?
                        ORDER BY display_order, start_date DESC NULLS LAST, id
                        """,
                this::mapExperience,
                profileId
        );
    }

    @Override
    public List<ProfileProject> listProjects(UUID profileId) {
        Map<UUID, List<ProjectTechnology>> technologiesByProject = listProjectTechnologies(profileId).stream()
                .collect(Collectors.groupingBy(ProjectTechnology::projectId));
        return jdbc.query("""
                        SELECT id, profile_id, name, url, description, display_order, created_at
                        FROM profile.projects
                        WHERE profile_id = ?
                        ORDER BY display_order, name, id
                        """,
                (resultSet, rowNum) -> mapProject(resultSet, technologiesByProject),
                profileId
        );
    }

    @Override
    public List<ProjectTechnology> listProjectTechnologies(UUID profileId) {
        return jdbc.query("""
                        SELECT technology.id, technology.project_id, technology.technology,
                               technology.normalized_technology, technology.display_order, technology.created_at
                        FROM profile.project_technologies technology
                        JOIN profile.projects project ON project.id = technology.project_id
                        WHERE project.profile_id = ?
                        ORDER BY project.display_order, technology.display_order, technology.technology, technology.id
                        """,
                this::mapTechnology,
                profileId
        );
    }

    @Override
    public ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate) {
        UserProfile profile = aggregate.profile();
        jdbc.update("""
                        INSERT INTO profile.profiles (id, full_name, email, avatar_url, summary, created_at, updated_at)
                        VALUES (?, ?, ?, NULL, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            full_name = EXCLUDED.full_name,
                            email = EXCLUDED.email,
                            summary = EXCLUDED.summary,
                            updated_at = EXCLUDED.updated_at
                        """,
                profile.id(),
                profile.fullName(),
                profile.email(),
                profile.summary(),
                Timestamp.from(profile.createdAt()),
                Timestamp.from(profile.updatedAt())
        );
        replaceChildren(aggregate);
        return findProfileAggregate(profile.id()).orElseThrow();
    }

    @Override
    public boolean deleteProfile(UUID profileId) {
        return jdbc.update("DELETE FROM profile.profiles WHERE id = ?", profileId) > 0;
    }

    private void replaceChildren(ProfileAggregate aggregate) {
        UUID profileId = aggregate.profile().id();
        jdbc.update("DELETE FROM profile.profile_contacts WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.profile_links WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.profile_skills WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.profile_languages WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.education WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.experiences WHERE profile_id = ?", profileId);
        jdbc.update("DELETE FROM profile.projects WHERE profile_id = ?", profileId);

        aggregate.contacts().forEach(this::insertContact);
        aggregate.links().forEach(this::insertLink);
        aggregate.skills().forEach(this::insertSkill);
        aggregate.languages().forEach(this::insertLanguage);
        aggregate.education().forEach(this::insertEducation);
        aggregate.experiences().forEach(this::insertExperience);
        aggregate.projects().forEach(project -> {
            insertProject(project);
            project.technologies().forEach(this::insertTechnology);
        });
    }

    private void insertContact(ProfileContact contact) {
        jdbc.update("""
                        INSERT INTO profile.profile_contacts
                            (id, profile_id, contact_type, contact_value, label, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                contact.id(), contact.profileId(), contact.contactType(), contact.contactValue(), contact.label(),
                Timestamp.from(contact.createdAt()), Timestamp.from(contact.updatedAt())
        );
    }

    private void insertLink(ProfileLink link) {
        jdbc.update("""
                        INSERT INTO profile.profile_links (id, profile_id, link_type, url, label, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                link.id(), link.profileId(), link.linkType(), link.url(), link.label(),
                Timestamp.from(link.createdAt()), Timestamp.from(link.updatedAt())
        );
    }

    private void insertSkill(ProfileSkill skill) {
        jdbc.update("""
                        INSERT INTO profile.profile_skills
                            (id, profile_id, skill, normalized_skill, category, display_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                skill.id(), skill.profileId(), skill.skill(), skill.normalizedSkill(), skill.category(),
                skill.displayOrder(), Timestamp.from(skill.createdAt())
        );
    }

    private void insertLanguage(ProfileLanguage language) {
        jdbc.update("""
                        INSERT INTO profile.profile_languages
                            (id, profile_id, language, normalized_language, proficiency, display_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                language.id(), language.profileId(), language.language(), language.normalizedLanguage(),
                language.proficiency(), language.displayOrder(), Timestamp.from(language.createdAt())
        );
    }

    private void insertEducation(Education education) {
        jdbc.update("""
                        INSERT INTO profile.education
                            (id, profile_id, institution, degree, field, location, start_date, end_date,
                             description, relevant_focus, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """,
                education.id(), education.profileId(), education.institution(), education.degree(), education.field(),
                education.location(), education.startDate(), education.endDate(), education.relevantFocus(),
                Timestamp.from(education.createdAt())
        );
    }

    private void insertExperience(Experience experience) {
        jdbc.update("""
                        INSERT INTO profile.experiences
                            (id, profile_id, company, title, location, start_date, end_date, description,
                             display_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                experience.id(), experience.profileId(), experience.company(), experience.title(), experience.location(),
                experience.startDate(), experience.endDate(), experience.description(), experience.displayOrder(),
                Timestamp.from(experience.createdAt())
        );
    }

    private void insertProject(ProfileProject project) {
        jdbc.update("""
                        INSERT INTO profile.projects
                            (id, profile_id, name, start_date, end_date, url, description, display_order, created_at)
                        VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                        """,
                project.id(), project.profileId(), project.name(), project.url(), project.description(),
                project.displayOrder(), Timestamp.from(project.createdAt())
        );
    }

    private void insertTechnology(ProjectTechnology technology) {
        jdbc.update("""
                        INSERT INTO profile.project_technologies
                            (id, project_id, technology, normalized_technology, display_order, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                technology.id(), technology.projectId(), technology.technology(), technology.normalizedTechnology(),
                technology.displayOrder(), Timestamp.from(technology.createdAt())
        );
    }

    private UserProfile mapProfile(ResultSet resultSet, int rowNum) throws SQLException {
        return new UserProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("full_name"),
                resultSet.getString("email"),
                resultSet.getString("summary"),
                null,
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                null
        );
    }

    private ProfileContact mapContact(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProfileContact(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("contact_type"),
                resultSet.getString("contact_value"),
                resultSet.getString("label"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private ProfileLink mapLink(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProfileLink(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("link_type"),
                resultSet.getString("url"),
                resultSet.getString("label"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private ProfileSkill mapSkill(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProfileSkill(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("skill"),
                resultSet.getString("normalized_skill"),
                resultSet.getString("category"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private ProfileLanguage mapLanguage(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProfileLanguage(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("language"),
                resultSet.getString("normalized_language"),
                resultSet.getString("proficiency"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private Education mapEducation(ResultSet resultSet, int rowNum) throws SQLException {
        return new Education(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("institution"),
                resultSet.getString("degree"),
                resultSet.getString("field"),
                resultSet.getString("location"),
                resultSet.getObject("start_date", LocalDate.class),
                resultSet.getObject("end_date", LocalDate.class),
                resultSet.getString("relevant_focus"),
                instant(resultSet, "created_at")
        );
    }

    private Experience mapExperience(ResultSet resultSet, int rowNum) throws SQLException {
        return new Experience(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("company"),
                resultSet.getString("title"),
                resultSet.getString("location"),
                resultSet.getObject("start_date", LocalDate.class),
                resultSet.getObject("end_date", LocalDate.class),
                resultSet.getString("description"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private ProfileProject mapProject(ResultSet resultSet, Map<UUID, List<ProjectTechnology>> technologiesByProject)
            throws SQLException {
        UUID projectId = resultSet.getObject("id", UUID.class);
        return new ProfileProject(
                projectId,
                resultSet.getObject("profile_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("url"),
                resultSet.getString("description"),
                technologiesByProject.getOrDefault(projectId, List.of()),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private ProjectTechnology mapTechnology(ResultSet resultSet, int rowNum) throws SQLException {
        return new ProjectTechnology(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("technology"),
                resultSet.getString("normalized_technology"),
                resultSet.getInt("display_order"),
                instant(resultSet, "created_at")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }
}
