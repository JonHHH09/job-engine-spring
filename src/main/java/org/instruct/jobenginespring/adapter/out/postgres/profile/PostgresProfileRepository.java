package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.ProfileIdentityCandidate;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch;
import org.instruct.jobenginespring.application.profile.ProjectUpdateResult;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.application.pagination.SearchCandidates;
import org.instruct.jobenginespring.application.search.SearchTerm;
import org.instruct.jobenginespring.application.search.SearchTextNormalizer;
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

    public static final int MAX_SEARCH_CANDIDATES = 500;
    private static final int SEARCH_FIELD_COUNT = 19;
    static final int MAX_POSTINGS_PER_TOKEN = (MAX_SEARCH_CANDIDATES + 1) * SEARCH_FIELD_COUNT;
    public static final String SEARCH_CANDIDATES_SQL = """
            WITH query_tokens AS (
                SELECT token FROM unnest(CAST(:queryTokens AS text[])) AS token
            ), query_prefixes AS (
                SELECT prefix, query_token
                FROM unnest(CAST(:queryPrefixes AS text[]), CAST(:prefixOwners AS text[]))
                    AS value(prefix, query_token)
            ), query_prefix_sets AS (
                SELECT query_token, array_agg(prefix ORDER BY prefix COLLATE "C") AS prefixes
                FROM query_prefixes
                GROUP BY query_token
            ), raw_hits AS (
                SELECT posting.profile_id, posting.field_key, query.token AS query_token, posting.weight,
                       posting.ordinal > :postingLimit AS truncated
                FROM query_tokens query
                CROSS JOIN LATERAL (
                    WITH RECURSIVE sample(term, profile_id, field_key, weight, ordinal) AS (
                        (
                            SELECT posting.term, posting.profile_id, posting.field_key, posting.weight, 1::bigint
                            FROM profile.search_terms posting
                            WHERE posting.term COLLATE "C" >= query.token COLLATE "C"
                              AND posting.term COLLATE "C" < (query.token || '~') COLLATE "C"
                            ORDER BY posting.term COLLATE "C", posting.profile_id, posting.field_key
                            LIMIT 1
                        )
                        UNION ALL
                        SELECT next.term, next.profile_id, next.field_key, next.weight, sample.ordinal + 1
                        FROM sample
                        CROSS JOIN LATERAL (
                            SELECT posting.term, posting.profile_id, posting.field_key, posting.weight
                            FROM profile.search_terms posting
                            WHERE posting.term COLLATE "C" >= query.token COLLATE "C"
                              AND posting.term COLLATE "C" < (query.token || '~') COLLATE "C"
                              AND (posting.term COLLATE "C", posting.profile_id, posting.field_key)
                                  > (sample.term COLLATE "C", sample.profile_id, sample.field_key)
                            ORDER BY posting.term COLLATE "C", posting.profile_id, posting.field_key
                            LIMIT 1
                        ) next
                        WHERE sample.ordinal < :postingFetchLimit
                    )
                    SELECT * FROM sample
                ) posting
                UNION ALL
                SELECT posting.profile_id, posting.field_key, prefix_set.query_token, posting.weight,
                       posting.ordinal > :postingLimit AS truncated
                FROM query_prefix_sets prefix_set
                CROSS JOIN LATERAL (
                    SELECT sample.*,
                           row_number() OVER (ORDER BY sample.term COLLATE "C", sample.profile_id, sample.field_key) AS ordinal
                    FROM (
                        SELECT posting.term, posting.profile_id, posting.field_key, posting.weight
                        FROM profile.search_terms posting
                        WHERE posting.term COLLATE "C" = ANY(prefix_set.prefixes)
                        ORDER BY posting.term COLLATE "C", posting.profile_id, posting.field_key
                        LIMIT :postingFetchLimit
                    ) sample
                ) posting
            ), posting_bounds AS (
                SELECT COALESCE(bool_or(truncated), false) AS posting_truncated FROM raw_hits
            ), posting_hits AS (
                SELECT DISTINCT profile_id, field_key, query_token, weight
                FROM raw_hits
                WHERE NOT truncated
            ), scored AS (
                SELECT profile_id, SUM(weight)::integer AS score
                FROM posting_hits
                GROUP BY profile_id
            ), matching AS (
                SELECT profile.*, scored.score, posting_bounds.posting_truncated
                FROM scored
                CROSS JOIN LATERAL (
                    SELECT * FROM profile.profiles profile WHERE profile.id = scored.profile_id LIMIT 1
                ) profile
                CROSS JOIN posting_bounds
                ORDER BY scored.score DESC, profile.full_name COLLATE "C", profile.id
                LIMIT :fetchLimit
            )
            SELECT * FROM matching ORDER BY score DESC, full_name COLLATE "C", id
            """;

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
                        LIMIT :limit
                        """)
                .param("limit", PageRequest.DEFAULT_LIMIT)
                .query(this::mapProfile)
                .list();
    }

    @Override
    public Page<UserProfile> listProfiles(PageRequest request) {
        var rows = jdbc.sql("""
                        WITH bounds AS (
                            SELECT COALESCE(CAST(:snapshotAt AS timestamptz), CURRENT_TIMESTAMP) AS snapshot_at
                        )
                        SELECT profile.id, profile.full_name, profile.email, profile.summary, profile.created_at,
                               profile.updated_at, profile.revision, bounds.snapshot_at
                        FROM profile.profiles profile
                        CROSS JOIN bounds
                        WHERE profile.created_at <= bounds.snapshot_at
                          AND (CAST(:cursorCreatedAt AS timestamptz) IS NULL
                               OR profile.created_at < :cursorCreatedAt
                               OR (profile.created_at = :cursorCreatedAt AND profile.id > :cursorId))
                        ORDER BY profile.created_at DESC, profile.id
                        LIMIT :fetchLimit
                        """)
                .param("snapshotAt", timestamp(request.cursor() == null ? null : request.cursor().snapshotAt()))
                .param("cursorCreatedAt", timestamp(request.cursor() == null ? null : request.cursor().createdAt()))
                .param("cursorId", request.cursor() == null ? null : request.cursor().id())
                .param("fetchLimit", request.limit() + 1)
                .query((resultSet, rowNumber) -> new ProfilePageRow(
                        mapProfile(resultSet, rowNumber), resultSet.getTimestamp("snapshot_at").toInstant()))
                .list();
        boolean hasMore = rows.size() > request.limit();
        var pageRows = rows.stream().limit(request.limit()).toList();
        var items = pageRows.stream().map(ProfilePageRow::profile).toList();
        var last = hasMore ? pageRows.getLast() : null;
        return new Page<>(items, last == null ? null
                : request.nextCursor(last.snapshotAt(), last.profile().createdAt(), last.profile().id()));
    }

    @Override
    public Page<ProfileAggregate> listProfileAggregates(PageRequest request) {
        var profiles = listProfiles(request);
        return new Page<>(aggregatesForProfiles(profiles.items()), profiles.nextCursor());
    }

    @Override
    public SearchCandidates<ProfileAggregate> searchProfileCandidates(List<String> queryTokens, int limit) {
        var prefixes = SearchTextNormalizer.prefixes(queryTokens);
        var ranked = namedJdbc.query(SEARCH_CANDIDATES_SQL, new MapSqlParameterSource()
                        .addValue("queryTokens", queryTokens.toArray(String[]::new))
                        .addValue("queryPrefixes", prefixes.values().toArray(String[]::new))
                        .addValue("prefixOwners", prefixes.owners().toArray(String[]::new))
                        .addValue("postingLimit", MAX_POSTINGS_PER_TOKEN)
                        .addValue("postingFetchLimit", MAX_POSTINGS_PER_TOKEN + 1)
                        .addValue("fetchLimit", MAX_SEARCH_CANDIDATES + 1),
                (resultSet, rowNumber) -> new RankedProfile(
                        mapProfile(resultSet, rowNumber), resultSet.getInt("score"),
                        resultSet.getBoolean("posting_truncated")));
        if (ranked.isEmpty()) {
            return new SearchCandidates<>(0, List.of());
        }
        boolean hasMore = ranked.size() > MAX_SEARCH_CANDIDATES || ranked.getFirst().postingTruncated();
        var profiles = ranked.stream().limit(MAX_SEARCH_CANDIDATES).map(RankedProfile::profile).toList();
        return new SearchCandidates<>(profiles.size(), hasMore, aggregatesForProfiles(profiles));
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
        rebuildSearchTerms(aggregate);
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
        rebuildSearchTerms(aggregate);
        return findProfileAggregate(profile.id());
    }

    @Override
    @Transactional
    public Optional<ProjectUpdateResult> updateProject(
            ProfileProject project,
            long expectedRevision,
            long newRevision,
            Instant profileUpdatedAt,
            List<ProjectTechnology> replacementTechnologies
    ) {
        int profileUpdated = jdbc.sql("""
                        UPDATE profile.profiles
                        SET updated_at = :updatedAt,
                            revision = :newRevision
                        WHERE id = :profileId
                          AND revision = :expectedRevision
                          AND EXISTS (
                              SELECT 1 FROM profile.projects
                              WHERE id = :projectId AND profile_id = :profileId
                          )
                        """)
                .param("profileId", project.profileId())
                .param("projectId", project.id())
                .param("expectedRevision", expectedRevision)
                .param("newRevision", newRevision)
                .param("updatedAt", Timestamp.from(profileUpdatedAt))
                .update();
        if (profileUpdated != 1) {
            return Optional.empty();
        }
        jdbc.sql("""
                        UPDATE profile.projects
                        SET name = :name,
                            url = :url,
                            description = :description,
                            display_order = :displayOrder
                        WHERE id = :projectId AND profile_id = :profileId
                        """)
                .param("projectId", project.id())
                .param("profileId", project.profileId())
                .param("name", project.name())
                .param("url", project.url())
                .param("description", project.description())
                .param("displayOrder", project.displayOrder())
                .update();
        if (replacementTechnologies != null) {
            jdbc.sql("DELETE FROM profile.project_technologies WHERE project_id = :projectId")
                    .param("projectId", project.id())
                    .update();
            batchInsert("""
                            INSERT INTO profile.project_technologies
                                (id, project_id, technology, normalized_technology, display_order, created_at)
                            VALUES (:id, :projectId, :technology, :normalizedTechnology, :displayOrder, :createdAt)
                            """, replacementTechnologies);
        }
        ProfileAggregate aggregate = findProfileAggregate(project.profileId()).orElseThrow();
        rebuildSearchTerms(aggregate);
        ProfileProject persistedProject = aggregate.projects().stream()
                .filter(candidate -> candidate.id().equals(project.id()))
                .findFirst()
                .orElseThrow();
        return Optional.of(new ProjectUpdateResult(persistedProject, aggregate.profile().revision()));
    }

    @Override
    @Transactional
    public boolean lockProfileForDeletion(UUID profileId) {
        return jdbc.sql("SELECT id FROM profile.profiles WHERE id = :profileId FOR UPDATE")
                .param("profileId", profileId)
                .query(UUID.class)
                .optional()
                .isPresent();
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

    private void rebuildSearchTerms(ProfileAggregate aggregate) {
        var profile = aggregate.profile();
        var terms = new ArrayList<SearchTerm>();
        addTerms(terms, profile.id(), "profile.fullName", profile.fullName(), 8);
        addTerms(terms, profile.id(), "profile.email", profile.email(), 5);
        addTerms(terms, profile.id(), "profile.summary", profile.summary(), 3);
        aggregate.contacts().forEach(value -> {
            addTerms(terms, profile.id(), "contacts:" + value.id() + ":type", value.contactType(), 2);
            addTerms(terms, profile.id(), "contacts:" + value.id() + ":value", value.contactValue(), 2);
            addTerms(terms, profile.id(), "contacts:" + value.id() + ":label", value.label(), 1);
        });
        aggregate.links().forEach(value -> {
            addTerms(terms, profile.id(), "links:" + value.id() + ":type", value.linkType(), 3);
            addTerms(terms, profile.id(), "links:" + value.id() + ":url", value.url(), 3);
            addTerms(terms, profile.id(), "links:" + value.id() + ":label", value.label(), 2);
        });
        aggregate.skills().forEach(value -> {
            addTerms(terms, profile.id(), "skills:" + value.id() + ":skill", value.skill(), 7);
            addTerms(terms, profile.id(), "skills:" + value.id() + ":normalized", value.normalizedSkill(), 7);
            addTerms(terms, profile.id(), "skills:" + value.id() + ":category", value.category(), 3);
        });
        aggregate.languages().forEach(value -> {
            addTerms(terms, profile.id(), "languages:" + value.id() + ":language", value.language(), 4);
            addTerms(terms, profile.id(), "languages:" + value.id() + ":normalized", value.normalizedLanguage(), 4);
            addTerms(terms, profile.id(), "languages:" + value.id() + ":proficiency", value.proficiency(), 2);
        });
        aggregate.education().forEach(value -> {
            addTerms(terms, profile.id(), "education:" + value.id() + ":institution", value.institution(), 4);
            addTerms(terms, profile.id(), "education:" + value.id() + ":degree", value.degree(), 3);
            addTerms(terms, profile.id(), "education:" + value.id() + ":field", value.field(), 4);
            addTerms(terms, profile.id(), "education:" + value.id() + ":location", value.location(), 2);
            addTerms(terms, profile.id(), "education:" + value.id() + ":focus", value.relevantFocus(), 3);
        });
        aggregate.experiences().forEach(value -> {
            addTerms(terms, profile.id(), "experience:" + value.id() + ":company", value.company(), 4);
            addTerms(terms, profile.id(), "experience:" + value.id() + ":title", value.title(), 6);
            addTerms(terms, profile.id(), "experience:" + value.id() + ":location", value.location(), 2);
            addTerms(terms, profile.id(), "experience:" + value.id() + ":description", value.description(), 3);
        });
        aggregate.projects().forEach(value -> {
            addTerms(terms, profile.id(), "projects:" + value.id() + ":name", value.name(), 5);
            addTerms(terms, profile.id(), "projects:" + value.id() + ":url", value.url(), 3);
            addTerms(terms, profile.id(), "projects:" + value.id() + ":description", value.description(), 3);
        });
        aggregate.projectTechnologies().forEach(value -> {
            addTerms(terms, profile.id(), "technologies:" + value.id() + ":technology", value.technology(), 5);
            addTerms(terms, profile.id(), "technologies:" + value.id() + ":normalized", value.normalizedTechnology(), 5);
        });
        jdbc.sql("DELETE FROM profile.search_terms WHERE profile_id = :profileId")
                .param("profileId", profile.id()).update();
        namedJdbc.batchUpdate("""
                        INSERT INTO profile.search_terms (profile_id, field_key, term, weight)
                        VALUES (:entityId, :fieldKey, :term, :weight)
                        """, terms.stream().map(term -> new MapSqlParameterSource()
                        .addValue("entityId", term.entityId()).addValue("fieldKey", term.fieldKey())
                        .addValue("term", term.term()).addValue("weight", term.weight()))
                .toArray(SqlParameterSource[]::new));
    }

    private static void addTerms(List<SearchTerm> terms, UUID profileId, String fieldKey, String value, int weight) {
        terms.addAll(SearchTerm.from(profileId, fieldKey, value, weight));
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

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record RankedProfile(UserProfile profile, int score, boolean postingTruncated) {
    }

    private record ProfilePageRow(UserProfile profile, Instant snapshotAt) {
    }
}
