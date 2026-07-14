package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.instruct.jobenginespring.application.search.SearchTextNormalizer;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Backfills legacy rows through the runtime Unicode normalizer.
 *
 * <p>The migration is a local maintenance boundary: transaction-held {@code SHARE ROW EXCLUSIVE}
 * locks block inserts, updates, and deletes on all source and target tables until the complete
 * backfill commits. Every read is keyset-paged and every JDBC result and insert batch is bounded.
 */
public final class V23__backfill_indexed_search_terms extends BaseJavaMigration {
    static final int ENTITY_BATCH_SIZE = 100;
    static final int CHILD_BATCH_SIZE = 250;
    static final int INSERT_BATCH_SIZE = 1_000;
    static final int ROW_FETCH_SIZE = 100;

    private static final String LOCK_TABLES = """
            LOCK TABLE
                job_schema.jobs, job_schema.job_skills, job_schema.search_terms,
                profile.profiles, profile.profile_contacts, profile.profile_links,
                profile.profile_skills, profile.profile_languages, profile.education,
                profile.experiences, profile.projects, profile.project_technologies,
                profile.search_terms
            IN SHARE ROW EXCLUSIVE MODE
            """;

    private static final EntitySource JOBS = new EntitySource("""
            WITH entity_batch AS (
                SELECT id, title, company, location, description, experience_requirement,
                       employment_type, seniority
                FROM job_schema.jobs
                WHERE (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid))
                ORDER BY id
                LIMIT ?
            )
            SELECT entity.id AS entity_id, field.field_key, field.value, field.weight
            FROM entity_batch entity
            CROSS JOIN LATERAL (VALUES
                ('job.title', entity.title, 8),
                ('job.company', entity.company, 5),
                ('job.location', entity.location, 3),
                ('job.description', entity.description, 3),
                ('job.experienceRequirement', entity.experience_requirement, 4),
                ('job.employmentType', entity.employment_type, 2),
                ('job.seniority', entity.seniority, 2)
            ) field(field_key, value, weight)
            ORDER BY entity.id, field.field_key
            """, "job_schema.search_terms", "job_id", List.of(
            childSource("job_schema.job_skills", "job_id", "('skills:' || child.id, child.skill, 7)")));

    private static final EntitySource PROFILES = new EntitySource("""
            WITH entity_batch AS (
                SELECT id, full_name, email, summary
                FROM profile.profiles
                WHERE (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid))
                ORDER BY id
                LIMIT ?
            )
            SELECT entity.id AS entity_id, field.field_key, field.value, field.weight
            FROM entity_batch entity
            CROSS JOIN LATERAL (VALUES
                ('profile.fullName', entity.full_name, 8),
                ('profile.email', entity.email, 5),
                ('profile.summary', entity.summary, 3)
            ) field(field_key, value, weight)
            ORDER BY entity.id, field.field_key
            """, "profile.search_terms", "profile_id", List.of(
            childSource("profile.profile_contacts", "profile_id", """
                    ('contacts:' || child.id || ':type', child.contact_type, 2),
                    ('contacts:' || child.id || ':value', child.contact_value, 2),
                    ('contacts:' || child.id || ':label', child.label, 1)
                    """),
            childSource("profile.profile_links", "profile_id", """
                    ('links:' || child.id || ':type', child.link_type, 3),
                    ('links:' || child.id || ':url', child.url, 3),
                    ('links:' || child.id || ':label', child.label, 2)
                    """),
            childSource("profile.profile_skills", "profile_id", """
                    ('skills:' || child.id || ':skill', child.skill, 7),
                    ('skills:' || child.id || ':normalized', child.normalized_skill, 7),
                    ('skills:' || child.id || ':category', child.category, 3)
                    """),
            childSource("profile.profile_languages", "profile_id", """
                    ('languages:' || child.id || ':language', child.language, 4),
                    ('languages:' || child.id || ':normalized', child.normalized_language, 4),
                    ('languages:' || child.id || ':proficiency', child.proficiency, 2)
                    """),
            childSource("profile.education", "profile_id", """
                    ('education:' || child.id || ':institution', child.institution, 4),
                    ('education:' || child.id || ':degree', child.degree, 3),
                    ('education:' || child.id || ':field', child.field, 4),
                    ('education:' || child.id || ':location', child.location, 2),
                    ('education:' || child.id || ':focus', child.relevant_focus, 3)
                    """),
            childSource("profile.experiences", "profile_id", """
                    ('experience:' || child.id || ':company', child.company, 4),
                    ('experience:' || child.id || ':title', child.title, 6),
                    ('experience:' || child.id || ':location', child.location, 2),
                    ('experience:' || child.id || ':description', child.description, 3)
                    """),
            childSource("profile.projects", "profile_id", """
                    ('projects:' || child.id || ':name', child.name, 5),
                    ('projects:' || child.id || ':url', child.url, 3),
                    ('projects:' || child.id || ':description', child.description, 3)
                    """),
            new ChildSource("""
                    WITH child_batch AS (
                        SELECT technology.id, project.profile_id, technology.technology,
                               technology.normalized_technology
                        FROM profile.project_technologies technology
                        JOIN profile.projects project ON project.id = technology.project_id
                        WHERE project.profile_id = ANY (?)
                          AND (CAST(? AS uuid) IS NULL OR technology.id > CAST(? AS uuid))
                        ORDER BY technology.id
                        LIMIT ?
                    )
                    SELECT child.id AS child_id, child.profile_id AS entity_id,
                           field.field_key, field.value, field.weight
                    FROM child_batch child
                    CROSS JOIN LATERAL (VALUES
                        ('technologies:' || child.id || ':technology', child.technology, 5),
                        ('technologies:' || child.id || ':normalized', child.normalized_technology, 5)
                    ) field(field_key, value, weight)
                    ORDER BY child.id, field.field_key
                    """)));

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var lock = connection.createStatement()) {
            lock.execute(LOCK_TABLES);
        }
        backfill(connection, JOBS);
        backfill(connection, PROFILES);
    }

    private static void backfill(Connection connection, EntitySource source) throws SQLException {
        try (var insert = new TermInserter(connection, source.targetTable(), source.targetIdColumn())) {
            UUID lastEntityId = null;
            while (true) {
                var entityIds = readEntityBatch(connection, source, lastEntityId, insert);
                if (entityIds.isEmpty()) {
                    break;
                }
                for (var child : source.children()) {
                    readChildBatches(connection, child, entityIds, insert);
                }
                lastEntityId = entityIds.getLast();
            }
        }
    }

    private static List<UUID> readEntityBatch(Connection connection, EntitySource source, UUID after,
                                               TermInserter insert) throws SQLException {
        var ids = new java.util.ArrayList<UUID>(ENTITY_BATCH_SIZE);
        try (var statement = connection.prepareStatement(source.sql())) {
            statement.setFetchSize(ROW_FETCH_SIZE);
            statement.setObject(1, after);
            statement.setObject(2, after);
            statement.setInt(3, ENTITY_BATCH_SIZE);
            try (var rows = statement.executeQuery()) {
                UUID previous = null;
                while (rows.next()) {
                    var entityId = rows.getObject("entity_id", UUID.class);
                    if (!entityId.equals(previous)) {
                        ids.add(entityId);
                        previous = entityId;
                    }
                    insert.addTerms(rows, entityId);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static void readChildBatches(Connection connection, ChildSource source, List<UUID> entityIds,
                                         TermInserter insert) throws SQLException {
        UUID after = null;
        while (true) {
            UUID last = null;
            Array ids = connection.createArrayOf("uuid", entityIds.toArray());
            try (var statement = connection.prepareStatement(source.sql())) {
                statement.setFetchSize(ROW_FETCH_SIZE);
                statement.setArray(1, ids);
                statement.setObject(2, after);
                statement.setObject(3, after);
                statement.setInt(4, CHILD_BATCH_SIZE);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        last = rows.getObject("child_id", UUID.class);
                        insert.addTerms(rows, rows.getObject("entity_id", UUID.class));
                    }
                }
            } finally {
                ids.free();
            }
            if (last == null) {
                return;
            }
            after = last;
        }
    }

    private static ChildSource childSource(String table, String parentColumn, String fields) {
        return new ChildSource("""
                WITH child_batch AS (
                    SELECT *
                    FROM %s
                    WHERE %s = ANY (?)
                      AND (CAST(? AS uuid) IS NULL OR id > CAST(? AS uuid))
                    ORDER BY id
                    LIMIT ?
                )
                SELECT child.id AS child_id, child.%s AS entity_id,
                       field.field_key, field.value, field.weight
                FROM child_batch child
                CROSS JOIN LATERAL (VALUES
                    %s
                ) field(field_key, value, weight)
                ORDER BY child.id, field.field_key
                """.formatted(table, parentColumn, parentColumn, fields));
    }

    private record EntitySource(String sql, String targetTable, String targetIdColumn, List<ChildSource> children) {
        private EntitySource {
            children = List.copyOf(children);
        }
    }

    private record ChildSource(String sql) {
    }

    private static final class TermInserter implements AutoCloseable {
        private final PreparedStatement statement;
        private int pending;

        private TermInserter(Connection connection, String table, String idColumn) throws SQLException {
            statement = connection.prepareStatement("INSERT INTO " + table + " (" + idColumn
                    + ", field_key, term, weight) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING");
        }

        private void addTerms(ResultSet row, UUID entityId) throws SQLException {
            for (var term : SearchTextNormalizer.tokens(row.getString("value"))) {
                statement.setObject(1, entityId);
                statement.setString(2, row.getString("field_key"));
                statement.setString(3, term);
                statement.setInt(4, row.getInt("weight"));
                statement.addBatch();
                pending++;
                if (pending == INSERT_BATCH_SIZE) {
                    flush();
                }
            }
        }

        private void flush() throws SQLException {
            statement.executeBatch();
            pending = 0;
        }

        @Override
        public void close() throws SQLException {
            if (pending > 0) {
                flush();
            }
            statement.close();
        }
    }
}
