package org.instruct.jobenginespring.adapter.out.postgres.match;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.domain.match.*;
import org.instruct.jobenginespring.application.pagination.PageRequest;
import org.instruct.jobenginespring.support.CountingDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresMatchAnalysisRepositoryIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");
    private static final UUID PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("20000000-0000-0000-0000-000000000002");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine").withUsername("test").withPassword("test");
    private static JdbcTemplate jdbc;
    private static CountingDataSource dataSource;
    private PostgresMatchAnalysisRepository repository;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").defaultSchema("profile")
                .schemas("profile", "document", "job_schema", "match").load().migrate();
        dataSource = new CountingDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles, job_schema.jobs CASCADE");
        jdbc.update("INSERT INTO profile.profiles VALUES (?,?,?,NULL,NULL,?,?)", PROFILE, "Candidate", "safe@example.test",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO job_schema.jobs VALUES (?,?,?,?,NULL,NULL,?,NULL,NULL,NULL,NULL,?,?,?)",
                    JOB, "text", "test", "Engineer", "Safe description", "fingerprint",
                    java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO job_schema.job_text_ingestions
                        (id, job_id, source_label, input_text_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), JOB, "test", "match-fixture-hash", java.sql.Timestamp.from(NOW));
        });
        repository = new PostgresMatchAnalysisRepository(JdbcClient.create(jdbc), new ObjectMapper());
    }

    @Test void v16CreatesTablesAndRoundTripsFiltersAndIdempotency() {
        assertEquals(List.of("disagreements", "reports", "reviews"), jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='match' ORDER BY table_name", String.class));
        var report = report();
        assertEquals(report, repository.saveReport(report));
        assertEquals(report.id(), repository.saveReport(new MatchReport(UUID.randomUUID(), PROFILE, JOB, NOW, NOW,
                report.algorithmVersion(), 80, 100, report.outcome(), false, report.components(), report.evidence(), NOW)).id());
        assertEquals(1, repository.listReports(PROFILE, null).size());
        assertEquals(1, repository.listReports(PROFILE, JOB).size());
        assertTrue(repository.listReports(UUID.randomUUID(), null).isEmpty());

        var review = review(report.id());
        var saved = repository.saveReview(review);
        assertEquals(saved, repository.findReview(saved.id()).orElseThrow());
        assertEquals(List.of(saved), repository.listReviews(report.id()));
        assertEquals(saved.id(), repository.saveReview(new MatchReview(UUID.randomUUID(), review.reportId(), review.reviewer(),
                review.model(), review.reviewVersion(), review.overallScore(), review.outcome(), review.blockerMismatch(),
                review.components().reversed(), review.evidence().reversed(), review.summary(), NOW.plusSeconds(1))).id());
        var disagreement = disagreement(report.id(), saved.id());
        assertEquals(disagreement.id(), repository.saveDisagreement(disagreement).id());
        assertEquals(disagreement.id(), repository.saveDisagreement(disagreement).id());
        assertEquals(disagreement, repository.findDisagreement(disagreement.id()).orElseThrow());
        assertEquals(List.of(disagreement), repository.listDisagreements(report.id()));
        assertEquals("divergence-v1", disagreement.policyVersion());
        var equivalentReview = repository.saveReview(new MatchReview(UUID.randomUUID(), report.id(), "second-reviewer",
                "second-model", "v2", review.overallScore(), review.outcome(), review.blockerMismatch(),
                review.components(), review.evidence(), "outcome_adjustment", NOW.plusSeconds(2)));
        var equivalentDisagreement = new MatchDisagreement(disagreement.id(), report.id(), equivalentReview.id(),
                disagreement.policyVersion(), disagreement.reasons(), disagreement.evidenceDefectCodes(),
                disagreement.status(), null, NOW.plusSeconds(2), NOW.plusSeconds(2));
        assertEquals(disagreement.id(), repository.saveDisagreement(equivalentDisagreement).id());
        assertEquals(1, repository.listDisagreements(report.id()).size());
        assertTrue(repository.listDisagreements(UUID.randomUUID()).isEmpty());
        assertThrows(Exception.class, () -> repository.saveReview(new MatchReview(UUID.randomUUID(), UUID.randomUUID(),
                "human", "provider-neutral", "v1", 50, MatchOutcome.PARTIAL_MATCH, false,
                List.of(), List.of(), "review_consistent", NOW)));
    }

    @Test void malformedPersistedJsonIsRejectedSafely() {
        var reportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO match.reports (
                    id, profile_id, job_id, profile_revision, job_revision, algorithm_version,
                    overall_score, confidence, outcome, blocker_mismatch, components, evidence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """, reportId, PROFILE, JOB, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                "invalid-json-shape-v1", 0, 0, MatchOutcome.INSUFFICIENT_EVIDENCE.name(), false,
                "[{\"component\":\"TECHNICAL\"}]", "[]", java.sql.Timestamp.from(NOW));

        var error = assertThrows(Exception.class, () -> repository.findReport(reportId));
        assertNotNull(error.getCause());
    }

    @Test void historyIsImmutableButEscalationAcknowledgementIsMutableAndParentCascadeApplies() {
        var report = repository.saveReport(report());
        var review = repository.saveReview(review(report.id()));
        var disagreement = repository.saveDisagreement(disagreement(report.id(), review.id()));
        assertThrows(Exception.class, () -> jdbc.update("UPDATE match.reports SET overall_score=1 WHERE id=?", report.id()));
        assertThrows(Exception.class, () -> jdbc.update("UPDATE match.reviews SET overall_score=1 WHERE id=?", review.id()));

        var acknowledged = repository.updateDisagreement(new MatchDisagreement(disagreement.id(), report.id(), review.id(),
                disagreement.policyVersion(), disagreement.reasons(), disagreement.evidenceDefectCodes(),
                DisagreementStatus.ACKNOWLEDGED, "JOB-54", disagreement.createdAt(), NOW.plusSeconds(1)));
        assertEquals("JOB-54", repository.findDisagreement(acknowledged.id()).orElseThrow().linearIssueId());
        jdbc.update("DELETE FROM profile.profiles WHERE id=?", PROFILE);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM match.reports", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM match.reviews", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM match.disagreements", Integer.class));

        setUp();
        repository.saveReport(report());
        jdbc.update("DELETE FROM job_schema.jobs WHERE id=?", JOB);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM match.reports", Integer.class));
    }

    @Test void supportsUnfilteredAndJobFilteredLookupsAndMissingUpdates() {
        var report = repository.saveReport(report());
        assertEquals(report, repository.findReport(report.id()).orElseThrow());
        assertTrue(repository.findReport(UUID.randomUUID()).isEmpty());
        assertEquals(List.of(report), repository.listReports(null, null));
        assertEquals(List.of(report), repository.listReports(null, JOB));
        assertTrue(repository.listReports(null, UUID.randomUUID()).isEmpty());

        var review = repository.saveReview(review(report.id()));
        var disagreement = repository.saveDisagreement(disagreement(report.id(), review.id()));
        assertEquals(List.of(disagreement), repository.listDisagreements(null));
        assertTrue(repository.findReview(UUID.randomUUID()).isEmpty());
        assertTrue(repository.findDisagreement(UUID.randomUUID()).isEmpty());
        var missing = new MatchDisagreement(UUID.randomUUID(), report.id(), review.id(), disagreement.policyVersion(),
                disagreement.reasons(), disagreement.evidenceDefectCodes(), DisagreementStatus.ACKNOWLEDGED, null, NOW, NOW);
        assertThrows(IllegalArgumentException.class, () -> repository.updateDisagreement(missing));
    }

    @Test void reportPagesAndJoinedRevisionLookupRemainBoundedAsHistoryGrows() {
        for (int index = 0; index < 40; index++) {
            var base = report();
            repository.saveReport(new MatchReport(
                    UUID.randomUUID(), PROFILE, JOB, NOW, NOW, "bounded-v" + index,
                    base.overallScore(), base.confidence(), base.outcome(), base.blockerMismatch(),
                    base.components(), base.evidence(), NOW.plusSeconds(index)
            ));
        }
        dataSource.reset();

        var firstPage = repository.listReports(null, null,
                PageRequest.of(5, null, "match-reports", "profile=null;job=null"));

        assertEquals(5, firstPage.items().size());
        assertNotNull(firstPage.nextCursor());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(6, dataSource.rowsRead());
        assertNull(repository.listReports(null, null,
                PageRequest.of(100, null, "match-reports", "profile=null;job=null")).nextCursor());

        dataSource.reset();
        var joined = repository.findReportWithRevisions(firstPage.items().getFirst().report().id()).orElseThrow();

        assertEquals(NOW, joined.currentProfileRevision());
        assertEquals(NOW, joined.currentJobRevision());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(1, dataSource.rowsRead());
    }

    @Test void reportCursorSurvivesDeletesAndJoinedUpdatesAndKeepsFirstPageWatermark() {
        var newest = saveReportAt("cursor-newest", NOW.plusSeconds(3));
        var middle = saveReportAt("cursor-middle", NOW.plusSeconds(2));
        var oldest = saveReportAt("cursor-oldest", NOW.plusSeconds(1));
        var firstRequest = PageRequest.of(1, null, "match-reports", "profile=null;job=null");
        var firstPage = repository.listReports(null, null, firstRequest);
        var resumed = PageRequest.of(1, firstPage.nextCursor(), "match-reports", "profile=null;job=null");

        jdbc.update("DELETE FROM match.reports WHERE id=?", newest.id());
        jdbc.update("UPDATE profile.profiles SET updated_at=? WHERE id=?",
                Timestamp.from(NOW.plusSeconds(20)), PROFILE);
        saveReportAt("cursor-later", resumed.cursor().snapshotAt().plusSeconds(1));

        var secondPage = repository.listReports(null, null, resumed);
        var thirdPage = repository.listReports(null, null, PageRequest.of(
                1, secondPage.nextCursor(), "match-reports", "profile=null;job=null"));

        assertEquals(middle.id(), secondPage.items().getFirst().report().id());
        assertEquals(NOW.plusSeconds(20), secondPage.items().getFirst().currentProfileRevision());
        assertEquals(oldest.id(), thirdPage.items().getFirst().report().id());
        assertNull(thirdPage.nextCursor());
    }

    @Test void reviewAndDisagreementPagesAreBoundedAndCursorsRejectChangedFilters() {
        var report = repository.saveReport(report());
        for (int index = 0; index < 25; index++) {
            repository.saveReview(reviewAt(report.id(), "reviewer-" + index, NOW.plusSeconds(index)));
        }
        dataSource.reset();

        var reviews = repository.listReviews(report.id(),
                PageRequest.of(5, null, "match-reviews", "report=" + report.id()));

        assertEquals(5, reviews.items().size());
        assertNotNull(reviews.nextCursor());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(6, dataSource.rowsRead());
        assertThrows(RuntimeException.class, () -> PageRequest.of(5, reviews.nextCursor(),
                "match-reviews", "report=" + UUID.randomUUID()));
        assertEquals(5, repository.listReviews(null,
                PageRequest.of(5, null, "match-reviews", "report=null")).items().size());

        var firstReview = reviews.items().getFirst();
        repository.saveDisagreement(disagreementAt(report.id(), firstReview.id(),
                Set.of(DisagreementReason.OVERALL_DELTA), NOW.plusSeconds(1)));
        repository.saveDisagreement(disagreementAt(report.id(), reviews.items().get(1).id(),
                Set.of(DisagreementReason.OUTCOME_MISMATCH), NOW.plusSeconds(2)));
        repository.saveDisagreement(disagreementAt(report.id(), reviews.items().get(2).id(),
                Set.of(DisagreementReason.BLOCKER_MISMATCH), NOW.plusSeconds(3)));
        dataSource.reset();

        var disagreements = repository.listDisagreements(null,
                PageRequest.of(2, null, "match-disagreements", "report=null"));

        assertEquals(2, disagreements.items().size());
        assertNotNull(disagreements.nextCursor());
        assertEquals(1, dataSource.statementExecutions());
        assertEquals(3, dataSource.rowsRead());
        assertThrows(RuntimeException.class, () -> PageRequest.of(2, disagreements.nextCursor(),
                "match-disagreements", "report=" + report.id()));
    }

    @Test void historyCursorsSurviveDeletionMutationAndRowsAfterTheWatermark() {
        var report = repository.saveReport(report());
        var oldestReview = repository.saveReview(reviewAt(report.id(), "oldest", NOW.plusSeconds(1)));
        var middleReview = repository.saveReview(reviewAt(report.id(), "middle", NOW.plusSeconds(2)));
        var newestReview = repository.saveReview(reviewAt(report.id(), "newest", NOW.plusSeconds(3)));
        var reviewRequest = PageRequest.of(1, null, "match-reviews", "report=" + report.id());
        var firstReviews = repository.listReviews(report.id(), reviewRequest);
        var reviewCursor = PageRequest.of(1, firstReviews.nextCursor(), "match-reviews", "report=" + report.id());

        jdbc.update("DELETE FROM match.reviews WHERE id=?", newestReview.id());
        var postSnapshotReview = repository.saveReview(reviewAt(report.id(), "post-snapshot-review",
                reviewCursor.cursor().snapshotAt().plusSeconds(1)));

        var secondReviews = repository.listReviews(report.id(), reviewCursor);
        var thirdReviews = repository.listReviews(report.id(), PageRequest.of(1, secondReviews.nextCursor(),
                "match-reviews", "report=" + report.id()));
        assertEquals(middleReview.id(), secondReviews.items().getFirst().id());
        assertEquals(oldestReview.id(), thirdReviews.items().getFirst().id());
        assertNull(thirdReviews.nextCursor());

        var oldestDisagreement = repository.saveDisagreement(disagreementAt(report.id(), oldestReview.id(),
                Set.of(DisagreementReason.OVERALL_DELTA), NOW.plusSeconds(1)));
        var middleDisagreement = repository.saveDisagreement(disagreementAt(report.id(), middleReview.id(),
                Set.of(DisagreementReason.OUTCOME_MISMATCH), NOW.plusSeconds(2)));
        var newestDisagreement = repository.saveDisagreement(disagreementAt(report.id(), postSnapshotReview.id(),
                Set.of(DisagreementReason.BLOCKER_MISMATCH), NOW.plusSeconds(3)));
        var disagreementRequest = PageRequest.of(1, null, "match-disagreements", "report=" + report.id());
        var firstDisagreements = repository.listDisagreements(report.id(), disagreementRequest);
        var disagreementCursor = PageRequest.of(1, firstDisagreements.nextCursor(), "match-disagreements",
                "report=" + report.id());

        jdbc.update("DELETE FROM match.disagreements WHERE id=?", newestDisagreement.id());
        repository.updateDisagreement(new MatchDisagreement(middleDisagreement.id(), report.id(), middleReview.id(),
                middleDisagreement.policyVersion(), middleDisagreement.reasons(), middleDisagreement.evidenceDefectCodes(),
                DisagreementStatus.ACKNOWLEDGED, null, middleDisagreement.createdAt(), NOW.plusSeconds(20)));
        repository.saveDisagreement(disagreementAt(report.id(), postSnapshotReview.id(),
                Set.of(DisagreementReason.EVIDENCE_DEFECT), disagreementCursor.cursor().snapshotAt().plusSeconds(1)));

        var secondDisagreements = repository.listDisagreements(report.id(), disagreementCursor);
        var thirdDisagreements = repository.listDisagreements(report.id(), PageRequest.of(1,
                secondDisagreements.nextCursor(), "match-disagreements", "report=" + report.id()));
        assertEquals(middleDisagreement.id(), secondDisagreements.items().getFirst().id());
        assertEquals(DisagreementStatus.ACKNOWLEDGED, secondDisagreements.items().getFirst().status());
        assertEquals(oldestDisagreement.id(), thirdDisagreements.items().getFirst().id());
        assertNull(thirdDisagreements.nextCursor());
    }

    @Test void historyCursorsTraverseEqualTimestampsInCanonicalDescendingUuidOrder() {
        var report = repository.saveReport(report());
        var reviews = java.util.stream.IntStream.range(0, 3).mapToObj(index -> {
                    var value = reviewAt(report.id(), "tie-" + index, NOW);
                    return repository.saveReview(new MatchReview(value.id(), value.reportId(), value.reviewer(),
                            value.model(), value.reviewVersion(), value.overallScore() + index, value.outcome(),
                            value.blockerMismatch(), value.components(), value.evidence(), value.summary(), NOW));
                })
                .toList();
        var expectedReviews = reviews.stream().map(MatchReview::id)
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();

        var traversedReviews = new java.util.ArrayList<UUID>();
        String reviewCursor = null;
        do {
            var page = repository.listReviews(report.id(), PageRequest.of(1, reviewCursor,
                    "match-reviews", "report=" + report.id()));
            traversedReviews.addAll(page.items().stream().map(MatchReview::id).toList());
            reviewCursor = page.nextCursor();
        } while (reviewCursor != null);
        assertEquals(expectedReviews, traversedReviews);

        var reasons = List.of(DisagreementReason.OVERALL_DELTA, DisagreementReason.OUTCOME_MISMATCH,
                DisagreementReason.BLOCKER_MISMATCH);
        var disagreements = java.util.stream.IntStream.range(0, 3).mapToObj(index -> repository.saveDisagreement(
                disagreementAt(report.id(), reviews.get(index).id(), Set.of(reasons.get(index)), NOW))).toList();
        var expectedDisagreements = disagreements.stream().map(MatchDisagreement::id)
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();

        var traversedDisagreements = new java.util.ArrayList<UUID>();
        String disagreementCursor = null;
        do {
            var page = repository.listDisagreements(report.id(), PageRequest.of(1, disagreementCursor,
                    "match-disagreements", "report=" + report.id()));
            traversedDisagreements.addAll(page.items().stream().map(MatchDisagreement::id).toList());
            disagreementCursor = page.nextCursor();
        } while (disagreementCursor != null);
        assertEquals(expectedDisagreements, traversedDisagreements);
    }

    @Test void exactProductionHistoryQueriesUseKeysetIndexesAtScaleWithNormalAndGenericPlans() {
        var indexNames = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'match' AND indexname IN (
                    'reports_list_created_id_idx', 'reports_profile_created_id_idx',
                    'reports_job_created_id_idx', 'reports_profile_job_created_id_idx',
                    'reviews_list_created_id_idx', 'reviews_report_created_id_idx',
                    'disagreements_list_created_id_idx', 'disagreements_report_created_id_idx')
                ORDER BY indexname
                """, String.class);
        assertEquals(List.of("disagreements_list_created_id_idx", "disagreements_report_created_id_idx",
                "reports_job_created_id_idx", "reports_list_created_id_idx", "reports_profile_created_id_idx",
                "reports_profile_job_created_id_idx", "reviews_list_created_id_idx",
                "reviews_report_created_id_idx"), indexNames);

        var unfilteredReport = repository.saveReport(report());
        var filteredReport = saveReportAt("history-plan-filter", NOW.plusSeconds(1));
        var noiseProfile = UUID.randomUUID();
        var noiseJob = UUID.randomUUID();
        jdbc.update("INSERT INTO profile.profiles VALUES (?,?,?,NULL,NULL,?,?)", noiseProfile,
                "Planner Noise", "planner-noise@example.test", Timestamp.from(NOW), Timestamp.from(NOW));
        var seedTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        seedTransaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO job_schema.jobs VALUES (?,?,?,?,NULL,NULL,?,NULL,NULL,NULL,NULL,?,?,?)",
                    noiseJob, "text", "planner-noise", "Planner Noise", "Safe planner description",
                    "planner-noise-fingerprint", Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO job_schema.job_text_ingestions
                        (id, job_id, source_label, input_text_hash, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), noiseJob, "planner-noise", "planner-noise-hash", Timestamp.from(NOW));
        });
        jdbc.update("""
                INSERT INTO match.reports (
                    id, profile_id, job_id, profile_revision, job_revision, algorithm_version,
                    overall_score, confidence, outcome, blocker_mismatch, components, evidence, created_at)
                SELECT md5('plan-report-' || value)::uuid,
                       CASE WHEN value <= 5000 OR value BETWEEN 10001 AND 15000
                            THEN ?::uuid ELSE ?::uuid END,
                       CASE WHEN value <= 10000 THEN ?::uuid ELSE ?::uuid END,
                       ?::timestamptz - value * interval '1 second',
                       ?::timestamptz - value * interval '1 second',
                       'planner-v' || value, 50, 50, 'PARTIAL_MATCH', false,
                       '[]'::jsonb, '[]'::jsonb,
                       ?::timestamptz - value * interval '1 second'
                FROM generate_series(1, 15100) value
                """, noiseProfile, PROFILE, noiseJob, JOB, Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO match.reviews (
                    id, fingerprint, report_id, reviewer, model, review_version, overall_score, outcome,
                    blocker_mismatch, components, evidence, summary, created_at)
                SELECT md5('plan-review-' || value)::uuid,
                       md5('plan-review-fingerprint-' || value)::uuid,
                       CASE WHEN value <= 5000 THEN ?::uuid ELSE ?::uuid END,
                       'plan-reviewer', 'plan-model', 'v1', 50, 'PARTIAL_MATCH', false,
                       '[]'::jsonb, '[]'::jsonb, 'review_consistent',
                       ?::timestamptz - value * interval '1 second'
                FROM generate_series(1, 5100) value
                """, unfilteredReport.id(), filteredReport.id(), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO match.disagreements (
                    id, fingerprint, report_id, review_id, policy_version, reasons,
                    evidence_defect_codes, status, created_at, updated_at)
                SELECT md5('plan-disagreement-' || review.id)::uuid,
                       md5('plan-disagreement-fingerprint-' || review.id)::uuid,
                       review.report_id, review.id, 'divergence-v1', '["OVERALL_DELTA"]'::jsonb,
                       '[]'::jsonb, 'PENDING_ESCALATION', review.created_at, review.created_at
                FROM match.reviews review
                WHERE review.reviewer = 'plan-reviewer'
                """);
        jdbc.execute("ANALYZE match.reviews");
        jdbc.execute("ANALYZE match.disagreements");
        jdbc.execute("ANALYZE match.reports");

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            try {
                assertPreparedReportPlansUse("reports_unfiltered",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_UNFILTERED_SQL,
                        null, null, "reports_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedReportPlansUse("reports_profile",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_PROFILE_SQL,
                        PROFILE, null, "reports_profile_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedReportPlansUse("reports_job",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_JOB_SQL,
                        null, JOB, "reports_job_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedReportPlansUse("reports_profile_job",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_PROFILE_JOB_SQL,
                        PROFILE, JOB, "reports_profile_job_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedPlansUse("reviews_unfiltered", PostgresMatchAnalysisRepository.REVIEW_PAGE_UNFILTERED_SQL,
                        null, "reviews_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedPlansUse("reviews_filtered", PostgresMatchAnalysisRepository.REVIEW_PAGE_FILTERED_SQL,
                        filteredReport.id(), "reviews_report_created_id_idx", NOW.minusSeconds(5_005));
                assertPreparedPlansUse("disagreements_unfiltered",
                        PostgresMatchAnalysisRepository.DISAGREEMENT_PAGE_UNFILTERED_SQL,
                        null, "disagreements_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedPlansUse("disagreements_filtered",
                        PostgresMatchAnalysisRepository.DISAGREEMENT_PAGE_FILTERED_SQL,
                        filteredReport.id(), "disagreements_report_created_id_idx", NOW.minusSeconds(5_005));

                jdbc.execute("DEALLOCATE ALL");
                jdbc.execute("SET LOCAL plan_cache_mode = force_generic_plan");
                assertPreparedReportPlansUse("reports_unfiltered_generic",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_UNFILTERED_SQL,
                        null, null, "reports_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedReportPlansUse("reports_profile_generic",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_PROFILE_SQL,
                        PROFILE, null, "reports_profile_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedReportPlansUse("reports_job_generic",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_JOB_SQL,
                        null, JOB, "reports_job_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedReportPlansUse("reports_profile_job_generic",
                        PostgresMatchAnalysisRepository.REPORT_PAGE_PROFILE_JOB_SQL,
                        PROFILE, JOB, "reports_profile_job_created_id_idx", NOW.minusSeconds(15_005));
                assertPreparedPlansUse("reviews_unfiltered_generic",
                        PostgresMatchAnalysisRepository.REVIEW_PAGE_UNFILTERED_SQL,
                        null, "reviews_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedPlansUse("reviews_filtered_generic",
                        PostgresMatchAnalysisRepository.REVIEW_PAGE_FILTERED_SQL,
                        filteredReport.id(), "reviews_report_created_id_idx", NOW.minusSeconds(5_005));
                assertPreparedPlansUse("disagreements_unfiltered_generic",
                        PostgresMatchAnalysisRepository.DISAGREEMENT_PAGE_UNFILTERED_SQL,
                        null, "disagreements_list_created_id_idx", NOW.minusSeconds(10));
                assertPreparedPlansUse("disagreements_filtered_generic",
                        PostgresMatchAnalysisRepository.DISAGREEMENT_PAGE_FILTERED_SQL,
                        filteredReport.id(), "disagreements_report_created_id_idx", NOW.minusSeconds(5_005));
            } finally {
                jdbc.execute("DEALLOCATE ALL");
            }
        });
    }

    @Test void joinedRevisionMapperHandlesMissingCurrentRows() throws Exception {
        var resultSet = mock(ResultSet.class);
        var method = PostgresMatchAnalysisRepository.class.getDeclaredMethod(
                "nullableInstant", ResultSet.class, String.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, resultSet, "current_revision"));
        verify(resultSet).getTimestamp("current_revision");
    }

    @Test void translatesSerializationFailuresWithoutLeakingPayloads() throws Exception {
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new IllegalStateException("private payload"));
        var broken = new PostgresMatchAnalysisRepository(JdbcClient.create(jdbc), mapper);

        var error = assertThrows(IllegalArgumentException.class, () -> broken.saveReport(report()));

        assertEquals("match data cannot be serialized", error.getMessage());
    }

    private static MatchReport report() {
        return new MatchReport(UUID.randomUUID(), PROFILE, JOB, NOW, NOW, "v1", 80, 100, MatchOutcome.STRONG_MATCH,
                false, List.of(new ComponentScore(MatchComponent.TECHNICAL, 40, 40, EvidenceStatus.MATCH)),
                List.of(new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.MATCH, "normalized_skill", "java", false)), NOW);
    }

    private MatchReport saveReportAt(String version, Instant createdAt) {
        var base = report();
        return repository.saveReport(new MatchReport(UUID.randomUUID(), PROFILE, JOB, NOW, NOW, version,
                base.overallScore(), base.confidence(), base.outcome(), base.blockerMismatch(),
                base.components(), base.evidence(), createdAt));
    }
    private static MatchReview review(UUID reportId) {
        return new MatchReview(UUID.randomUUID(), reportId, "human", "provider-neutral", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 20, 40, EvidenceStatus.PARTIAL),
                        new ComponentScore(MatchComponent.DELIVERY, 5, 10, EvidenceStatus.PARTIAL)),
                List.of(new MatchEvidence(MatchComponent.TECHNICAL, EvidenceStatus.PARTIAL, "normalized_skill", "java", false),
                        new MatchEvidence(MatchComponent.DELIVERY, EvidenceStatus.PARTIAL, "experience_fact", "delivered systems", false)),
                "score_adjustment", NOW);
    }
    private static MatchReview reviewAt(UUID reportId, String reviewer, Instant createdAt) {
        var value = review(reportId);
        return new MatchReview(UUID.randomUUID(), reportId, reviewer, value.model(), value.reviewVersion(),
                value.overallScore(), value.outcome(), value.blockerMismatch(), value.components(), value.evidence(),
                value.summary(), createdAt);
    }
    private static MatchDisagreement disagreement(UUID reportId, UUID reviewId) {
        var value = new MatchDisagreement(UUID.randomUUID(), reportId, reviewId, "divergence-v1",
                Set.of(DisagreementReason.OVERALL_DELTA), List.of(), DisagreementStatus.PENDING_ESCALATION, null, NOW, NOW);
        return new MatchDisagreement(value.fingerprint(), reportId, reviewId, value.policyVersion(), value.reasons(),
                value.evidenceDefectCodes(), value.status(), null, NOW, NOW);
    }
    private static MatchDisagreement disagreementAt(UUID reportId, UUID reviewId,
                                                     Set<DisagreementReason> reasons, Instant createdAt) {
        var id = MatchDisagreement.fingerprint(reportId, "divergence-v1", reasons, List.of());
        return new MatchDisagreement(id, reportId, reviewId, "divergence-v1", reasons, List.of(),
                DisagreementStatus.PENDING_ESCALATION, null, createdAt, createdAt);
    }
    private static void assertPreparedPlansUse(String name, String productionSql, UUID reportId,
                                               String indexName, Instant cursorCreatedAt) {
        boolean filtered = reportId != null;
        var parameterTypes = filtered
                ? "(uuid,timestamptz,timestamptz,uuid,integer)"
                : "(timestamptz,timestamptz,uuid,integer)";
        jdbc.execute("PREPARE " + name + parameterTypes + " AS " + positional(productionSql, filtered));
        var firstArguments = filtered ? "('" + reportId + "',NULL,NULL,NULL,5)" : "(NULL,NULL,NULL,5)";
        var resumedArguments = filtered
                ? "('" + reportId + "','" + NOW.plusSeconds(10) + "','" + cursorCreatedAt
                    + "','00000000-0000-0000-0000-000000000000',5)"
                : "('" + NOW.plusSeconds(10) + "','" + cursorCreatedAt
                    + "','00000000-0000-0000-0000-000000000000',5)";
        assertPlanUsesIndex(name, firstArguments, indexName);
        assertPlanUsesIndex(name, resumedArguments, indexName);
    }

    private static void assertPreparedReportPlansUse(String name, String productionSql, UUID profileId,
                                                     UUID jobId, String indexName, Instant cursorCreatedAt) {
        int filters = (profileId == null ? 0 : 1) + (jobId == null ? 0 : 1);
        var parameterTypes = switch (filters) {
            case 0 -> "(timestamptz,timestamptz,uuid,integer)";
            case 1 -> "(uuid,timestamptz,timestamptz,uuid,integer)";
            default -> "(uuid,uuid,timestamptz,timestamptz,uuid,integer)";
        };
        jdbc.execute("PREPARE " + name + parameterTypes + " AS "
                + reportPositional(productionSql, profileId != null, jobId != null));
        var filtersSql = profileId != null && jobId != null
                ? "'" + profileId + "','" + jobId + "',"
                : profileId != null ? "'" + profileId + "'," : jobId != null ? "'" + jobId + "'," : "";
        var firstArguments = "(" + filtersSql + "NULL,NULL,NULL,5)";
        var resumedArguments = "(" + filtersSql + "'" + NOW.plusSeconds(10) + "','" + cursorCreatedAt
                + "','ffffffff-ffff-ffff-ffff-ffffffffffff',5)";
        assertPlanUsesIndex(name, firstArguments, indexName);
        var resumedIndexes = profileId != null && jobId != null
                ? List.of(indexName, "reports_profile_created_id_idx", "reports_job_created_id_idx",
                    "reports_list_created_id_idx")
                : filters == 0 ? List.of(indexName) : List.of(indexName, "reports_list_created_id_idx");
        assertPlanUsesAnyIndex(name, resumedArguments, resumedIndexes);
    }

    private static void assertPlanUsesIndex(String name, String arguments, String indexName) {
        assertPlanUsesAnyIndex(name, arguments, List.of(indexName));
    }

    private static void assertPlanUsesAnyIndex(String name, String arguments, List<String> indexNames) {
        var plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) EXECUTE " + name + arguments, String.class));
        var used = indexNames.stream().filter(plan::contains).toList();
        assertFalse(used.isEmpty(), plan);
        assertFalse(plan.contains("Seq Scan on reports"), plan);
        assertFalse(plan.contains("Seq Scan on reviews"), plan);
        assertFalse(plan.contains("Seq Scan on disagreements"), plan);
        assertTrue(used.stream().mapToLong(index -> indexRowsExamined(plan, index)).sum() <= 20, plan);
    }

    private static long indexRowsExamined(String plan, String indexName) {
        var rows = Pattern.compile("actual [^)]*rows=(\\d+(?:\\.\\d+)?) loops=(\\d+)");
        return plan.lines().filter(line -> line.contains(indexName)).mapToLong(line -> {
            var matcher = rows.matcher(line);
            assertTrue(matcher.find(), line);
            return (long) Math.ceil(Double.parseDouble(matcher.group(1)) * Long.parseLong(matcher.group(2)));
        }).sum();
    }

    private static String positional(String sql, boolean filtered) {
        int offset = filtered ? 1 : 0;
        var positional = sql
                .replace(":snapshotAt", "$" + (offset + 1))
                .replace(":cursorCreatedAt", "$" + (offset + 2))
                .replace(":cursorId", "$" + (offset + 3))
                .replace(":fetchLimit", "$" + (offset + 4));
        return filtered ? positional.replace(":reportId", "$1") : positional;
    }

    private static String reportPositional(String sql, boolean profileFiltered, boolean jobFiltered) {
        int offset = 0;
        var positional = sql;
        if (profileFiltered) {
            positional = positional.replace(":profileId", "$" + ++offset);
        }
        if (jobFiltered) {
            positional = positional.replace(":jobId", "$" + ++offset);
        }
        return positional
                .replace(":snapshotAt", "$" + (offset + 1))
                .replace(":cursorCreatedAt", "$" + (offset + 2))
                .replace(":cursorId", "$" + (offset + 3))
                .replace(":fetchLimit", "$" + (offset + 4));
    }
}
