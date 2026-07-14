package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.ProfileIdentityCandidate;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch;
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
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "job-engine.profile.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresProfileRepository implements ProfileRepository {

    private static final RowMapper<ProfileContact> CONTACT_MAPPER = DataClassRowMapper.newInstance(ProfileContact.class);
    private static final RowMapper<ProfileLink> LINK_MAPPER = DataClassRowMapper.newInstance(ProfileLink.class);
    private static final RowMapper<ProfileSkill> SKILL_MAPPER = DataClassRowMapper.newInstance(ProfileSkill.class);
    private static final RowMapper<ProfileLanguage> LANGUAGE_MAPPER = DataClassRowMapper.newInstance(ProfileLanguage.class);
    private static final RowMapper<Education> EDUCATION_MAPPER = DataClassRowMapper.newInstance(Education.class);
    private static final RowMapper<Experience> EXPERIENCE_MAPPER = DataClassRowMapper.newInstance(Experience.class);
    private static final RowMapper<ProjectTechnology> TECHNOLOGY_MAPPER = DataClassRowMapper.newInstance(ProjectTechnology.class);

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations namedJdbc;

    public PostgresProfileRepository(NamedParameterJdbcOperations namedJdbc) {
        this.namedJdbc = Objects.requireNonNull(namedJdbc, "namedJdbc must not be null");
        this.jdbc = JdbcClient.create(namedJdbc);
    }

    @Override
    public List<UserProfile> listProfiles() {
        return jdbc.sql("""
                SELECT id, full_name, email, summary, created_at, updated_at, revision
                FROM profile.profiles
                ORDER BY full_name, id
                """)
                .query(this::mapProfile)
                .list();
    }

    @Override
    public List<ProfileAggregate> listProfileAggregates() {
        List<UserProfile> profiles = listProfiles();
        return aggregatesForProfiles(profiles);
    }

    @Override
    public Optional<UserProfile> findProfileById(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, full_name, email, summary, created_at, updated_at, revision
                        FROM profile.profiles
                        WHERE id = :profileId
                        """)
                .param("profileId", profileId)
                .query(this::mapProfile)
                .optional();
    }

    @Override
    public Optional<ProfileAggregate> findProfileAggregate(UUID profileId) {
        return findProfileById(profileId)
                .flatMap(profile -> aggregatesForProfiles(List.of(profile)).stream().findFirst());
    }

    @Override
    public List<ProfileContact> listContacts(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, contact_type, contact_value, label, created_at, updated_at
                        FROM profile.profile_contacts
                        WHERE profile_id = :profileId
                        ORDER BY contact_type, contact_value, id
                        """)
                .param("profileId", profileId)
                .query(CONTACT_MAPPER)
                .list();
    }

    @Override
    public List<ProfileLink> listLinks(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, link_type, url, label, created_at, updated_at
                        FROM profile.profile_links
                        WHERE profile_id = :profileId
                        ORDER BY link_type, url, id
                        """)
                .param("profileId", profileId)
                .query(LINK_MAPPER)
                .list();
    }

    @Override
    public List<ProfileSkill> listSkills(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, skill, normalized_skill, category, display_order, created_at
                        FROM profile.profile_skills
                        WHERE profile_id = :profileId
                        ORDER BY display_order, skill, id
                        """)
                .param("profileId", profileId)
                .query(SKILL_MAPPER)
                .list();
    }

    @Override
    public List<ProfileLanguage> listLanguages(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, language, normalized_language, proficiency, display_order, created_at
                        FROM profile.profile_languages
                        WHERE profile_id = :profileId
                        ORDER BY display_order, language, id
                        """)
                .param("profileId", profileId)
                .query(LANGUAGE_MAPPER)
                .list();
    }

    @Override
    public List<Education> listEducation(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, institution, degree, field, location, start_date, end_date,
                               relevant_focus, created_at
                        FROM profile.education
                        WHERE profile_id = :profileId
                        ORDER BY start_date NULLS LAST, institution, id
                        """)
                .param("profileId", profileId)
                .query(EDUCATION_MAPPER)
                .list();
    }

    @Override
    public List<Experience> listExperiences(UUID profileId) {
        return jdbc.sql("""
                        SELECT id, profile_id, company, title, location, start_date, end_date, description,
                               display_order, created_at
                        FROM profile.experiences
                        WHERE profile_id = :profileId
                        ORDER BY display_order, start_date DESC NULLS LAST, id
                        """)
                .param("profileId", profileId)
                .query(EXPERIENCE_MAPPER)
                .list();
    }

    @Override
    public List<ProfileProject> listProjects(UUID profileId) {
        Map<UUID, List<ProjectTechnology>> technologiesByProject = listProjectTechnologies(profileId).stream()
                .collect(Collectors.groupingBy(ProjectTechnology::projectId));
        return jdbc.sql("""
                        SELECT id, profile_id, name, url, description, display_order, created_at
                        FROM profile.projects
                        WHERE profile_id = :profileId
                        ORDER BY display_order, name, id
                        """)
                .param("profileId", profileId)
                .query((resultSet, rowNum) -> mapProject(resultSet, technologiesByProject))
                .list();
    }

    @Override
    public List<ProjectTechnology> listProjectTechnologies(UUID profileId) {
        return jdbc.sql("""
                        SELECT technology.id, technology.project_id, technology.technology,
                               technology.normalized_technology, technology.display_order, technology.created_at
                        FROM profile.project_technologies technology
                        JOIN profile.projects project ON project.id = technology.project_id
                        WHERE project.profile_id = :profileId
                        ORDER BY project.display_order, technology.display_order, technology.technology, technology.id
                        """)
                .param("profileId", profileId)
                .query(TECHNOLOGY_MAPPER)
                .list();
    }

    @Override
    @Transactional
    public ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate) {
        UserProfile profile = aggregate.profile();
        jdbc.sql("""
                        INSERT INTO profile.profiles (id, full_name, email, avatar_url, summary, created_at, updated_at, revision)
                        VALUES (:id, :fullName, :email, NULL, :summary, :createdAt, :updatedAt, :revision)
                        ON CONFLICT (id) DO UPDATE SET
                            full_name = EXCLUDED.full_name,
                            email = EXCLUDED.email,
                            summary = EXCLUDED.summary,
                            updated_at = EXCLUDED.updated_at,
                            revision = EXCLUDED.revision
                        """)
                .param("id", profile.id())
                .param("fullName", profile.fullName())
                .param("email", profile.email())
                .param("summary", profile.summary())
                .param("createdAt", Timestamp.from(profile.createdAt()))
                .param("updatedAt", Timestamp.from(profile.updatedAt()))
                .param("revision", profile.revision())
                .update();
        replaceChildren(aggregate);
        return findProfileAggregate(profile.id()).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<ProfileAggregate> replaceProfileAggregate(ProfileAggregate aggregate, long expectedRevision) {
        UserProfile profile = aggregate.profile();
        int updated = jdbc.sql("""
                        UPDATE profile.profiles
                        SET full_name = :fullName,
                            email = :email,
                            summary = :summary,
                            updated_at = :updatedAt,
                            revision = :revision
                        WHERE id = :id
                          AND revision = :expectedRevision
                        """)
                .param("id", profile.id())
                .param("fullName", profile.fullName())
                .param("email", profile.email())
                .param("summary", profile.summary())
                .param("updatedAt", Timestamp.from(profile.updatedAt()))
                .param("revision", profile.revision())
                .param("expectedRevision", expectedRevision)
                .update();
        if (updated != 1) {
            return Optional.empty();
        }
        replaceChildren(aggregate);
        return findProfileAggregate(profile.id());
    }

    @Override
    public boolean deleteProfile(UUID profileId) {
        return jdbc.sql("DELETE FROM profile.profiles WHERE id = :profileId")
                .param("profileId", profileId)
                .update() > 0;
    }

    @Override
    public List<ProfileIdentityCandidate> findIdentityCandidates(ProfileIdentitySearch search) {
        Objects.requireNonNull(search, "search must not be null");
        List<ProfileIdentityCandidate> candidates = new ArrayList<>();
        if (search.email() != null && !search.email().isBlank()) {
            candidates.addAll(jdbc.sql("""
                            SELECT id AS profile_id, 'email' AS matched_on
                            FROM profile.profiles
                            WHERE lower(btrim(email)) = :email
                            ORDER BY updated_at DESC, id
                            """)
                    .param("email", search.email().trim().toLowerCase(java.util.Locale.ROOT))
                    .query((resultSet, rowNumber) -> new ProfileIdentityCandidate(
                            resultSet.getObject("profile_id", UUID.class),
                            resultSet.getString("matched_on")
                    ))
                    .list());
        }
        for (ProfileIdentitySearch.LinkIdentity link : search.links()) {
            candidates.addAll(jdbc.sql("""
                            SELECT profile_id, 'link:' || lower(btrim(link_type)) AS matched_on
                            FROM profile.profile_links
                            WHERE lower(btrim(link_type)) = :linkType
                              AND url = :url
                            ORDER BY updated_at DESC, profile_id
                            """)
                    .param("linkType", link.linkType().trim().toLowerCase(java.util.Locale.ROOT))
                    .param("url", link.normalizedUrl())
                    .query((resultSet, rowNumber) -> new ProfileIdentityCandidate(
                            resultSet.getObject("profile_id", UUID.class),
                            resultSet.getString("matched_on")
                    ))
                    .list());
        }
        return candidates;
    }

    private void replaceChildren(ProfileAggregate aggregate) {
        UUID profileId = aggregate.profile().id();
        deleteOwnedRows("profile.profile_contacts", profileId);
        deleteOwnedRows("profile.profile_links", profileId);
        deleteOwnedRows("profile.profile_skills", profileId);
        deleteOwnedRows("profile.profile_languages", profileId);
        deleteOwnedRows("profile.education", profileId);
        deleteOwnedRows("profile.experiences", profileId);
        deleteOwnedRows("profile.projects", profileId);

        batchInsert("""
                        INSERT INTO profile.profile_contacts
                            (id, profile_id, contact_type, contact_value, label, created_at, updated_at)
                        VALUES (:id, :profileId, :contactType, :contactValue, :label, :createdAt, :updatedAt)
                        """, aggregate.contacts());
        batchInsert("""
                        INSERT INTO profile.profile_links
                            (id, profile_id, link_type, url, label, created_at, updated_at)
                        VALUES (:id, :profileId, :linkType, :url, :label, :createdAt, :updatedAt)
                        """, aggregate.links());
        batchInsert("""
                        INSERT INTO profile.profile_skills
                            (id, profile_id, skill, normalized_skill, category, display_order, created_at)
                        VALUES (:id, :profileId, :skill, :normalizedSkill, :category, :displayOrder, :createdAt)
                        """, aggregate.skills());
        batchInsert("""
                        INSERT INTO profile.profile_languages
                            (id, profile_id, language, normalized_language, proficiency, display_order, created_at)
                        VALUES (:id, :profileId, :language, :normalizedLanguage, :proficiency, :displayOrder, :createdAt)
                        """, aggregate.languages());
        batchInsert("""
                        INSERT INTO profile.education
                            (id, profile_id, institution, degree, field, location, start_date, end_date,
                             description, relevant_focus, created_at)
                        VALUES (:id, :profileId, :institution, :degree, :field, :location, :startDate, :endDate,
                                NULL, :relevantFocus, :createdAt)
                        """, aggregate.education());
        batchInsert("""
                        INSERT INTO profile.experiences
                            (id, profile_id, company, title, location, start_date, end_date, description,
                             display_order, created_at)
                        VALUES (:id, :profileId, :company, :title, :location, :startDate, :endDate, :description,
                                :displayOrder, :createdAt)
                        """, aggregate.experiences());
        batchInsert("""
                        INSERT INTO profile.projects
                            (id, profile_id, name, start_date, end_date, url, description, display_order, created_at)
                        VALUES (:id, :profileId, :name, NULL, NULL, :url, :description, :displayOrder, :createdAt)
                        """, aggregate.projects());
        batchInsert("""
                        INSERT INTO profile.project_technologies
                            (id, project_id, technology, normalized_technology, display_order, created_at)
                        VALUES (:id, :projectId, :technology, :normalizedTechnology, :displayOrder, :createdAt)
                        """, aggregate.projectTechnologies());
    }

    private List<ProfileAggregate> aggregatesForProfiles(List<UserProfile> profiles) {
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<UUID> profileIds = profiles.stream().map(UserProfile::id).toList();
        Map<UUID, List<ProfileContact>> contactsByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, contact_type, contact_value, label, created_at, updated_at
                        FROM profile.profile_contacts
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, contact_type, contact_value, id
                        """, CONTACT_MAPPER, ProfileContact::profileId);
        Map<UUID, List<ProfileLink>> linksByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, link_type, url, label, created_at, updated_at
                        FROM profile.profile_links
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, link_type, url, id
                        """, LINK_MAPPER, ProfileLink::profileId);
        Map<UUID, List<ProfileSkill>> skillsByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, skill, normalized_skill, category, display_order, created_at
                        FROM profile.profile_skills
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, display_order, skill, id
                        """, SKILL_MAPPER, ProfileSkill::profileId);
        Map<UUID, List<ProfileLanguage>> languagesByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, language, normalized_language, proficiency, display_order, created_at
                        FROM profile.profile_languages
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, display_order, language, id
                        """, LANGUAGE_MAPPER, ProfileLanguage::profileId);
        Map<UUID, List<Education>> educationByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, institution, degree, field, location, start_date, end_date,
                               relevant_focus, created_at
                        FROM profile.education
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, start_date NULLS LAST, institution, id
                        """, EDUCATION_MAPPER, Education::profileId);
        Map<UUID, List<Experience>> experiencesByProfileId = listByProfileId(profileIds, """
                        SELECT id, profile_id, company, title, location, start_date, end_date, description,
                               display_order, created_at
                        FROM profile.experiences
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, display_order, start_date DESC NULLS LAST, id
                        """, EXPERIENCE_MAPPER, Experience::profileId);
        Map<UUID, List<ProjectTechnology>> technologiesByProfileId = new LinkedHashMap<>();
        Map<UUID, List<ProjectTechnology>> technologiesByProjectId = new LinkedHashMap<>();
        for (UUID profileId : profileIds) {
            technologiesByProfileId.put(profileId, new ArrayList<>());
        }
        namedJdbc.query("""
                        SELECT technology.id, project.profile_id, technology.project_id, technology.technology,
                               technology.normalized_technology, technology.display_order, technology.created_at
                        FROM profile.project_technologies technology
                        JOIN profile.projects project ON project.id = technology.project_id
                        WHERE project.profile_id IN (:profileIds)
                        ORDER BY project.profile_id, project.display_order, technology.display_order,
                                 technology.technology, technology.id
                        """,
                new MapSqlParameterSource("profileIds", profileIds),
                (RowCallbackHandler) resultSet -> {
                    ProjectTechnology technology = TECHNOLOGY_MAPPER.mapRow(resultSet, 0);
                    UUID profileId = resultSet.getObject("profile_id", UUID.class);
                    technologiesByProfileId.get(profileId).add(technology);
                    technologiesByProjectId.computeIfAbsent(technology.projectId(), ignored -> new ArrayList<>()).add(technology);
                });
        Map<UUID, List<ProfileProject>> projectsByProfileId = new LinkedHashMap<>();
        for (UUID profileId : profileIds) {
            projectsByProfileId.put(profileId, new ArrayList<>());
        }
        namedJdbc.query("""
                        SELECT id, profile_id, name, url, description, display_order, created_at
                        FROM profile.projects
                        WHERE profile_id IN (:profileIds)
                        ORDER BY profile_id, display_order, name, id
                        """,
                new MapSqlParameterSource("profileIds", profileIds),
                (RowCallbackHandler) resultSet -> {
                    UUID projectId = resultSet.getObject("id", UUID.class);
                    UUID profileId = resultSet.getObject("profile_id", UUID.class);
                    projectsByProfileId.get(profileId)
                            .add(new ProfileProject(
                                    projectId,
                                    profileId,
                                    resultSet.getString("name"),
                                    resultSet.getString("url"),
                                    resultSet.getString("description"),
                                    List.copyOf(technologiesByProjectId.getOrDefault(projectId, List.of())),
                                    resultSet.getInt("display_order"),
                                    resultSet.getTimestamp("created_at").toInstant()
                            ));
                });
        return profiles.stream()
                .map(profile -> new ProfileAggregate(
                        profile,
                        List.copyOf(contactsByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(linksByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(skillsByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(languagesByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(educationByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(experiencesByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(projectsByProfileId.getOrDefault(profile.id(), List.of())),
                        List.copyOf(technologiesByProfileId.getOrDefault(profile.id(), List.of()))
                ))
                .toList();
    }

    private <T> Map<UUID, List<T>> listByProfileId(
            List<UUID> profileIds,
            String sql,
            RowMapper<T> mapper,
            java.util.function.Function<T, UUID> profileIdExtractor
    ) {
        Map<UUID, List<T>> valuesByProfileId = new LinkedHashMap<>();
        for (UUID profileId : profileIds) {
            valuesByProfileId.put(profileId, new ArrayList<>());
        }
        namedJdbc.query(sql, new MapSqlParameterSource("profileIds", profileIds), (RowCallbackHandler) resultSet -> {
            T value = mapper.mapRow(resultSet, 0);
            valuesByProfileId.get(profileIdExtractor.apply(value)).add(value);
        });
        return valuesByProfileId;
    }

    private void deleteOwnedRows(String table, UUID profileId) {
        jdbc.sql("DELETE FROM " + table + " WHERE profile_id = :profileId")
                .param("profileId", profileId)
                .update();
    }

    private void batchInsert(String sql, List<?> rows) {
        if (!rows.isEmpty()) {
            namedJdbc.batchUpdate(sql, rows.stream()
                    .map(PostgresProfileRepository::recordParameters)
                    .toArray(SqlParameterSource[]::new));
        }
    }

    private static SqlParameterSource recordParameters(Object record) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value = ReflectionUtils.invokeMethod(component.getAccessor(), record);
            parameters.addValue(component.getName(), value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
        return parameters;
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
                null,
                resultSet.getLong("revision")
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


    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }
}
