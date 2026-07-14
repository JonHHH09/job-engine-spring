package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.instruct.jobenginespring.application.search.SearchTextNormalizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Backfills legacy rows through the same canonical Unicode normalizer used by runtime writes and scoring. */
public final class V20__backfill_indexed_search_terms extends BaseJavaMigration {
    private static final int BATCH_SIZE = 1_000;

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        backfill(connection, jobFields(), "job_schema.search_terms", "job_id");
        backfill(connection, profileFields(), "profile.search_terms", "profile_id");
    }

    private static void backfill(Connection connection, String fieldsSql, String table, String idColumn)
            throws SQLException {
        try (var fields = connection.prepareStatement(fieldsSql);
             var rows = fields.executeQuery();
             var insert = connection.prepareStatement("INSERT INTO " + table + " (" + idColumn
                     + ", field_key, term, weight) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            int pending = 0;
            while (rows.next()) {
                pending = addTerms(rows, insert, pending);
                if (pending >= BATCH_SIZE) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
    }

    private static int addTerms(ResultSet row, PreparedStatement insert, int pending) throws SQLException {
        UUID entityId = row.getObject("entity_id", UUID.class);
        String fieldKey = row.getString("field_key");
        int weight = row.getInt("weight");
        for (var term : SearchTextNormalizer.tokens(row.getString("value"))) {
            insert.setObject(1, entityId);
            insert.setString(2, fieldKey);
            insert.setString(3, term);
            insert.setInt(4, weight);
            insert.addBatch();
            pending++;
        }
        return pending;
    }

    private static String jobFields() {
        return """
                SELECT job.id entity_id, field.field_key, field.value, field.weight
                FROM job_schema.jobs job
                CROSS JOIN LATERAL (VALUES
                    ('job.title', job.title, 8), ('job.company', job.company, 5),
                    ('job.location', job.location, 3), ('job.description', job.description, 3),
                    ('job.experienceRequirement', job.experience_requirement, 4),
                    ('job.employmentType', job.employment_type, 2), ('job.seniority', job.seniority, 2)
                ) field(field_key, value, weight)
                UNION ALL
                SELECT skill.job_id, 'skills:' || skill.id, skill.skill, 7 FROM job_schema.job_skills skill
                """;
    }

    private static String profileFields() {
        return """
                SELECT profile.id entity_id, field.field_key, field.value, field.weight
                FROM profile.profiles profile
                CROSS JOIN LATERAL (VALUES
                    ('profile.fullName', profile.full_name, 8), ('profile.email', profile.email, 5),
                    ('profile.summary', profile.summary, 3)
                ) field(field_key, value, weight)
                UNION ALL
                SELECT profile_id, 'contacts:' || id || ':type', contact_type, 2 FROM profile.profile_contacts
                UNION ALL SELECT profile_id, 'contacts:' || id || ':value', contact_value, 2 FROM profile.profile_contacts
                UNION ALL SELECT profile_id, 'contacts:' || id || ':label', label, 1 FROM profile.profile_contacts
                UNION ALL SELECT profile_id, 'links:' || id || ':type', link_type, 3 FROM profile.profile_links
                UNION ALL SELECT profile_id, 'links:' || id || ':url', url, 3 FROM profile.profile_links
                UNION ALL SELECT profile_id, 'links:' || id || ':label', label, 2 FROM profile.profile_links
                UNION ALL SELECT profile_id, 'skills:' || id || ':skill', skill, 7 FROM profile.profile_skills
                UNION ALL SELECT profile_id, 'skills:' || id || ':normalized', normalized_skill, 7 FROM profile.profile_skills
                UNION ALL SELECT profile_id, 'skills:' || id || ':category', category, 3 FROM profile.profile_skills
                UNION ALL SELECT profile_id, 'languages:' || id || ':language', language, 4 FROM profile.profile_languages
                UNION ALL SELECT profile_id, 'languages:' || id || ':normalized', normalized_language, 4 FROM profile.profile_languages
                UNION ALL SELECT profile_id, 'languages:' || id || ':proficiency', proficiency, 2 FROM profile.profile_languages
                UNION ALL SELECT profile_id, 'education:' || id || ':institution', institution, 4 FROM profile.education
                UNION ALL SELECT profile_id, 'education:' || id || ':degree', degree, 3 FROM profile.education
                UNION ALL SELECT profile_id, 'education:' || id || ':field', field, 4 FROM profile.education
                UNION ALL SELECT profile_id, 'education:' || id || ':location', location, 2 FROM profile.education
                UNION ALL SELECT profile_id, 'education:' || id || ':focus', relevant_focus, 3 FROM profile.education
                UNION ALL SELECT profile_id, 'experience:' || id || ':company', company, 4 FROM profile.experiences
                UNION ALL SELECT profile_id, 'experience:' || id || ':title', title, 6 FROM profile.experiences
                UNION ALL SELECT profile_id, 'experience:' || id || ':location', location, 2 FROM profile.experiences
                UNION ALL SELECT profile_id, 'experience:' || id || ':description', description, 3 FROM profile.experiences
                UNION ALL SELECT profile_id, 'projects:' || id || ':name', name, 5 FROM profile.projects
                UNION ALL SELECT profile_id, 'projects:' || id || ':url', url, 3 FROM profile.projects
                UNION ALL SELECT profile_id, 'projects:' || id || ':description', description, 3 FROM profile.projects
                UNION ALL
                SELECT project.profile_id, 'technologies:' || technology.id || ':technology', technology.technology, 5
                FROM profile.project_technologies technology JOIN profile.projects project ON project.id = technology.project_id
                UNION ALL
                SELECT project.profile_id, 'technologies:' || technology.id || ':normalized', technology.normalized_technology, 5
                FROM profile.project_technologies technology JOIN profile.projects project ON project.id = technology.project_id
                """;
    }
}
