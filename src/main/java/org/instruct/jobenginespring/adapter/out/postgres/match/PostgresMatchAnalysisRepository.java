package org.instruct.jobenginespring.adapter.out.postgres.match;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.instruct.jobenginespring.application.match.port.MatchAnalysisRepository;
import org.instruct.jobenginespring.application.pagination.Page;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.domain.match.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "job-engine.job.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresMatchAnalysisRepository implements MatchAnalysisRepository {
    private static final TypeReference<List<ComponentScore>> COMPONENTS = new TypeReference<>() {};
    private static final TypeReference<List<MatchEvidence>> EVIDENCE = new TypeReference<>() {};
    private static final TypeReference<Set<DisagreementReason>> REASONS = new TypeReference<>() {};
    private static final TypeReference<List<String>> DEFECT_CODES = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PostgresMatchAnalysisRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override public MatchReport saveReport(MatchReport r) {
        jdbc.sql("""
                INSERT INTO match.reports (id, profile_id, job_id, profile_revision, job_revision, algorithm_version,
                  overall_score, confidence, outcome, blocker_mismatch, components, evidence, created_at)
                VALUES (:id,:profileId,:jobId,:profileRevision,:jobRevision,:version,:score,:confidence,:outcome,
                  :blocker,CAST(:components AS jsonb),CAST(:evidence AS jsonb),:createdAt)
                ON CONFLICT ON CONSTRAINT reports_revision_unique DO NOTHING
                """).param("id", r.id()).param("profileId", r.profileId()).param("jobId", r.jobId())
                .param("profileRevision", Timestamp.from(r.profileRevision())).param("jobRevision", Timestamp.from(r.jobRevision()))
                .param("version", r.algorithmVersion()).param("score", r.overallScore()).param("confidence", r.confidence())
                .param("outcome", r.outcome().name()).param("blocker", r.blockerMismatch())
                .param("components", jsonb(r.components())).param("evidence", jsonb(r.evidence()))
                .param("createdAt", Timestamp.from(r.createdAt())).update();
        return jdbc.sql("""
                SELECT * FROM match.reports WHERE algorithm_version=:version AND profile_id=:profileId
                  AND profile_revision=:profileRevision AND job_id=:jobId AND job_revision=:jobRevision
                """).param("version", r.algorithmVersion()).param("profileId", r.profileId())
                .param("profileRevision", Timestamp.from(r.profileRevision())).param("jobId", r.jobId())
                .param("jobRevision", Timestamp.from(r.jobRevision())).query(this::report).single();
    }
    @Override public Optional<MatchReport> findReport(UUID id) { return jdbc.sql("SELECT * FROM match.reports WHERE id=:id").param("id",id).query(this::report).optional(); }
    @Override public Optional<ReportWithRevisions> findReportWithRevisions(UUID id) {
        return jdbc.sql("""
                SELECT report.*, profile.updated_at AS current_profile_revision,
                       job.updated_at AS current_job_revision
                FROM match.reports report
                LEFT JOIN profile.profiles profile ON profile.id = report.profile_id
                LEFT JOIN job_schema.jobs job ON job.id = report.job_id
                WHERE report.id = :id
                """).param("id", id).query(this::reportWithRevisions).optional();
    }
    @Override public List<MatchReport> listReports(UUID profileId, UUID jobId) {
        return jdbc.sql("""
                SELECT * FROM match.reports WHERE (CAST(:profileId AS uuid) IS NULL OR profile_id=:profileId)
                AND (CAST(:jobId AS uuid) IS NULL OR job_id=:jobId) ORDER BY created_at DESC,id
                """)
                .param("profileId",profileId).param("jobId",jobId).query(this::report).list();
    }
    @Override public Page<ReportWithRevisions> listReports(UUID profileId, UUID jobId, PageRequest request) {
        var rows = jdbc.sql("""
                WITH bounds AS (
                    SELECT COALESCE(CAST(:snapshotAt AS timestamptz), CURRENT_TIMESTAMP) AS snapshot_at
                )
                SELECT report.*, profile.updated_at AS current_profile_revision,
                       job.updated_at AS current_job_revision, bounds.snapshot_at
                FROM match.reports report
                LEFT JOIN profile.profiles profile ON profile.id = report.profile_id
                LEFT JOIN job_schema.jobs job ON job.id = report.job_id
                CROSS JOIN bounds
                WHERE (CAST(:profileId AS uuid) IS NULL OR report.profile_id = :profileId)
                  AND (CAST(:jobId AS uuid) IS NULL OR report.job_id = :jobId)
                  AND report.created_at <= bounds.snapshot_at
                  AND (CAST(:cursorCreatedAt AS timestamptz) IS NULL OR report.created_at < :cursorCreatedAt
                       OR (report.created_at = :cursorCreatedAt AND report.id > :cursorId))
                ORDER BY report.created_at DESC, report.id
                LIMIT :fetchLimit
                """)
                .param("profileId", profileId)
                .param("jobId", jobId)
                .param("snapshotAt", timestamp(request.cursor() == null ? null : request.cursor().snapshotAt()))
                .param("cursorCreatedAt", timestamp(request.cursor() == null ? null : request.cursor().createdAt()))
                .param("cursorId", request.cursor() == null ? null : request.cursor().id())
                .param("fetchLimit", request.limit() + 1)
                .query((resultSet, rowNumber) -> new ReportPageRow(
                        reportWithRevisions(resultSet, rowNumber), instant(resultSet, "snapshot_at")))
                .list();
        boolean hasMore = rows.size() > request.limit();
        var pageRows = rows.stream().limit(request.limit()).toList();
        var items = pageRows.stream().map(ReportPageRow::value).toList();
        var last = hasMore ? pageRows.getLast() : null;
        return new Page<>(items, last == null ? null : request.nextCursor(last.snapshotAt(),
                last.value().report().createdAt(), last.value().report().id()));
    }
    @Override public MatchReview saveReview(MatchReview r) {
        jdbc.sql("""
                INSERT INTO match.reviews (id,fingerprint,report_id,reviewer,model,review_version,overall_score,outcome,
                blocker_mismatch,components,evidence,summary,created_at) VALUES
                (:id,:fingerprint,:reportId,:reviewer,:model,:version,:score,:outcome,:blocker,CAST(:components AS jsonb),CAST(:evidence AS jsonb),:summary,:createdAt)
                ON CONFLICT (fingerprint) DO NOTHING
                """)
                .param("id",r.id()).param("fingerprint",r.fingerprint()).param("reportId",r.reportId()).param("reviewer",r.reviewer()).param("model",r.model())
                .param("version",r.reviewVersion()).param("score",r.overallScore()).param("outcome",r.outcome().name())
                .param("blocker",r.blockerMismatch()).param("components",jsonb(r.components())).param("evidence",jsonb(r.evidence()))
                .param("summary",r.summary()).param("createdAt",Timestamp.from(r.createdAt())).update();
        return jdbc.sql("SELECT * FROM match.reviews WHERE fingerprint=:fingerprint")
                .param("fingerprint", r.fingerprint()).query(this::review).single();
    }
    @Override public Optional<MatchReview> findReview(UUID id) { return jdbc.sql("SELECT * FROM match.reviews WHERE id=:id").param("id",id).query(this::review).optional(); }
    @Override public List<MatchReview> listReviews(UUID reportId) { return jdbc.sql("SELECT * FROM match.reviews WHERE report_id=:id ORDER BY created_at DESC,id").param("id",reportId).query(this::review).list(); }
    @Override public MatchDisagreement saveDisagreement(MatchDisagreement d) {
        jdbc.sql("""
                INSERT INTO match.disagreements (id,fingerprint,report_id,review_id,policy_version,reasons,evidence_defect_codes,
                status,linear_issue_id,created_at,updated_at)
                VALUES (:id,:fingerprint,:reportId,:reviewId,:policyVersion,CAST(:reasons AS jsonb),CAST(:defectCodes AS jsonb),
                :status,:issue,:createdAt,:updatedAt)
                ON CONFLICT (fingerprint) DO NOTHING
                """).param("id",d.id()).param("fingerprint",d.fingerprint()).param("reportId",d.reportId())
                .param("reviewId",d.reviewId()).param("policyVersion",d.policyVersion()).param("reasons",jsonb(d.reasons()))
                .param("defectCodes",jsonb(d.evidenceDefectCodes())).param("status",d.status().name())
                .param("issue",d.linearIssueId()).param("createdAt",Timestamp.from(d.createdAt())).param("updatedAt",Timestamp.from(d.updatedAt())).update();
        return jdbc.sql("SELECT * FROM match.disagreements WHERE fingerprint=:fingerprint").param("fingerprint",d.fingerprint()).query(this::disagreement).single();
    }
    @Override public Optional<MatchDisagreement> findDisagreement(UUID id) {
        return jdbc.sql("SELECT * FROM match.disagreements WHERE id=:id").param("id", id).query(this::disagreement).optional();
    }
    @Override public List<MatchDisagreement> listDisagreements(UUID reportId) {
        return jdbc.sql("SELECT * FROM match.disagreements WHERE (CAST(:id AS uuid) IS NULL OR report_id=:id) ORDER BY created_at DESC,id")
                .param("id",reportId).query(this::disagreement).list();
    }
    @Override public MatchDisagreement updateDisagreement(MatchDisagreement d) {
        var updated = jdbc.sql("UPDATE match.disagreements SET status=:status,linear_issue_id=:issue,updated_at=:updatedAt WHERE id=:id")
                .param("status",d.status().name()).param("issue",d.linearIssueId()).param("updatedAt",Timestamp.from(d.updatedAt())).param("id",d.id()).update();
        if (updated != 1) throw new IllegalArgumentException("match disagreement not found: " + d.id());
        return d;
    }
    private MatchReport report(ResultSet rs, int row) throws SQLException { return new MatchReport(uuid(rs,"id"),uuid(rs,"profile_id"),uuid(rs,"job_id"),
            instant(rs,"profile_revision"),instant(rs,"job_revision"),rs.getString("algorithm_version"),rs.getInt("overall_score"),rs.getInt("confidence"),
            MatchOutcome.valueOf(rs.getString("outcome")),rs.getBoolean("blocker_mismatch"),read(rs,"components",COMPONENTS),read(rs,"evidence",EVIDENCE),instant(rs,"created_at")); }
    private ReportWithRevisions reportWithRevisions(ResultSet rs, int row) throws SQLException {
        return new ReportWithRevisions(report(rs, row), nullableInstant(rs, "current_profile_revision"),
                nullableInstant(rs, "current_job_revision"));
    }
    private MatchReview review(ResultSet rs,int row)throws SQLException{return new MatchReview(uuid(rs,"id"),uuid(rs,"report_id"),rs.getString("reviewer"),rs.getString("model"),
            rs.getString("review_version"),rs.getInt("overall_score"),MatchOutcome.valueOf(rs.getString("outcome")),rs.getBoolean("blocker_mismatch"),read(rs,"components",COMPONENTS),read(rs,"evidence",EVIDENCE),rs.getString("summary"),instant(rs,"created_at"));}
    private MatchDisagreement disagreement(ResultSet rs,int row)throws SQLException{return new MatchDisagreement(uuid(rs,"id"),uuid(rs,"report_id"),uuid(rs,"review_id"),
            rs.getString("policy_version"),read(rs,"reasons",REASONS),read(rs,"evidence_defect_codes",DEFECT_CODES),
            DisagreementStatus.valueOf(rs.getString("status")),rs.getString("linear_issue_id"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private String jsonb(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("match data cannot be serialized",e);}}
    private <T>T read(ResultSet rs,String column,TypeReference<T> type)throws SQLException{try{return json.readValue(rs.getString(column),type);}catch(Exception e){throw new SQLException("invalid persisted match data",e);}}
    private static UUID uuid(ResultSet rs,String c)throws SQLException{return rs.getObject(c,UUID.class);} private static Instant instant(ResultSet rs,String c)throws SQLException{return rs.getTimestamp(c).toInstant();}
    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private record ReportPageRow(ReportWithRevisions value, Instant snapshotAt) {}
}
