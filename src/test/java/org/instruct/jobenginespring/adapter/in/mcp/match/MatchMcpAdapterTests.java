package org.instruct.jobenginespring.adapter.in.mcp.match;

import org.junit.jupiter.api.Test;
import org.instruct.jobenginespring.application.error.ApplicationErrorResponse;
import org.instruct.jobenginespring.application.match.MatchAnalysisService;
import org.instruct.jobenginespring.domain.match.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MatchMcpAdapterTests {
    @Test
    void exposesOnlyRequiredObjectShapedTools() {
        var methods = Stream.of(MatchMcpAdapter.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .toList();

        assertEquals(Set.of("analyze_job_match", "analyze_all_job_matches", "get_match_report", "list_match_reports",
                        "submit_match_review", "get_match_review", "list_match_reviews", "list_match_disagreements",
                        "acknowledge_match_disagreement", "link_match_disagreement"),
                methods.stream().map(method -> method.getAnnotation(McpTool.class).name()).collect(Collectors.toSet()));
        assertTrue(methods.stream().allMatch(method -> method.getParameterCount() == 1
                && method.getParameterTypes()[0].isRecord()
                && method.getParameterTypes()[0].getRecordComponents().length > 0));
    }

    @Test
    void delegatesLinkDisagreementThroughItsOwnTool() {
        var service = mock(MatchAnalysisService.class);
        var adapter = new MatchMcpAdapter(service);
        var disagreement = disagreement();
        when(service.linkDisagreement(disagreement.id(), "JOB-54")).thenReturn(disagreement);

        var result = adapter.link(new MatchMcpAdapter.LinkRequest(disagreement.id(), "JOB-54"));

        assertFalse(result.isError());
        assertSame(disagreement, result.structuredContent());
        verify(service).linkDisagreement(disagreement.id(), "JOB-54");
    }

    @Test
    void delegatesAnalyzeAndReturnsStructuredSuccess() {
        var service = mock(MatchAnalysisService.class);
        var adapter = new MatchMcpAdapter(service);
        var profileId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var report = new MatchReport(UUID.randomUUID(), profileId, jobId, Instant.EPOCH, Instant.EPOCH, "v1", 0, 0,
                MatchOutcome.INSUFFICIENT_EVIDENCE, false, List.of(), List.of(), Instant.EPOCH);
        var expected = new MatchAnalysisService.ReportView(report, false);
        when(service.analyze(profileId, jobId)).thenReturn(expected);

        var result = adapter.analyze(new MatchMcpAdapter.AnalyzeRequest(profileId, jobId));

        assertFalse(result.isError());
        assertSame(expected, result.structuredContent());
        verify(service).analyze(profileId, jobId);
    }

    @Test
    void sanitizesValidationAndInternalErrors() {
        var service = mock(MatchAnalysisService.class);
        var adapter = new MatchMcpAdapter(service);
        var profileId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("invalid request")).when(service).analyze(profileId, jobId);
        var validation = adapter.analyze(new MatchMcpAdapter.AnalyzeRequest(profileId, jobId));
        assertEquals("validation_error", assertInstanceOf(ApplicationErrorResponse.class, validation.structuredContent()).code());

        reset(service);
        doThrow(new IllegalStateException("secret-token private-host")).when(service).analyze(profileId, jobId);
        var internal = adapter.analyze(new MatchMcpAdapter.AnalyzeRequest(profileId, jobId));
        var error = assertInstanceOf(ApplicationErrorResponse.class, internal.structuredContent());
        assertEquals("internal_error", error.code());
        assertFalse(error.message().contains("secret-token"));
    }

    @Test
    void delegatesEveryRemainingOperationAndWrapsListResults() {
        var service = mock(MatchAnalysisService.class);
        var adapter = new MatchMcpAdapter(service);
        var profileId = UUID.randomUUID();
        var jobId = UUID.randomUUID();
        var reportId = UUID.randomUUID();
        var reviewId = UUID.randomUUID();
        var disagreementId = UUID.randomUUID();
        var report = report(profileId, jobId, reportId);
        var view = new MatchAnalysisService.ReportView(report, false);
        var review = review(reportId, reviewId);
        var disagreement = new MatchDisagreement(disagreementId, reportId, reviewId, "divergence-v1",
                Set.of(DisagreementReason.OVERALL_DELTA), List.of(), DisagreementStatus.ACKNOWLEDGED, null,
                Instant.EPOCH, Instant.EPOCH);
        var batch = new MatchAnalysisService.BatchResult(List.of(view), List.of());
        var reviewResult = new MatchAnalysisService.ReviewResult(review, disagreement);
        when(service.analyzeAll(profileId)).thenReturn(batch);
        when(service.getReport(reportId)).thenReturn(view);
        when(service.listReports(profileId, jobId)).thenReturn(List.of(view));
        when(service.submitReview(any())).thenReturn(reviewResult);
        when(service.getReview(reviewId)).thenReturn(review);
        when(service.listReviews(reportId)).thenReturn(List.of(review));
        when(service.listDisagreements(reportId)).thenReturn(List.of(disagreement));
        when(service.acknowledgeDisagreement(disagreementId, null)).thenReturn(disagreement);

        assertSame(batch, adapter.analyzeAll(new MatchMcpAdapter.ProfileRequest(profileId)).structuredContent());
        assertSame(view, adapter.getReport(new MatchMcpAdapter.IdRequest(reportId)).structuredContent());
        assertEquals(List.of(view), assertInstanceOf(MatchMcpAdapter.Reports.class,
                adapter.listReports(new MatchMcpAdapter.ReportFilter(profileId, jobId)).structuredContent()).reports());
        var request = new MatchMcpAdapter.ReviewRequest(reportId, "human", "provider-neutral", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false, review.components(), review.evidence(), "score_adjustment");
        assertSame(reviewResult, adapter.submitReview(request).structuredContent());
        assertSame(review, adapter.getReview(new MatchMcpAdapter.IdRequest(reviewId)).structuredContent());
        assertEquals(List.of(review), assertInstanceOf(MatchMcpAdapter.Reviews.class,
                adapter.listReviews(new MatchMcpAdapter.ReportRequest(reportId)).structuredContent()).reviews());
        assertEquals(List.of(disagreement), assertInstanceOf(MatchMcpAdapter.Disagreements.class,
                adapter.listDisagreements(new MatchMcpAdapter.DisagreementFilter(reportId)).structuredContent()).disagreements());
        assertSame(disagreement, adapter.acknowledge(
                new MatchMcpAdapter.AcknowledgeRequest(disagreementId, null)).structuredContent());

        verify(service).submitReview(argThat(draft -> draft.reportId().equals(reportId)
                && draft.summary().equals("score_adjustment")));
    }

    @Test
    void nullRequestsReturnStructuredValidationErrors() {
        var adapter = new MatchMcpAdapter(mock(MatchAnalysisService.class));

        for (var result : List.of(adapter.analyze(null), adapter.analyzeAll(null), adapter.getReport(null),
                adapter.listReports(null), adapter.submitReview(null), adapter.getReview(null),
                adapter.listReviews(null), adapter.listDisagreements(null), adapter.acknowledge(null), adapter.link(null))) {
            assertTrue(result.isError());
            assertEquals("validation_error", assertInstanceOf(ApplicationErrorResponse.class,
                    result.structuredContent()).code());
        }
    }

    @Test
    void responseRecordsDefensivelyCopyTheirLists() {
        assertThrows(NullPointerException.class, () -> new MatchMcpAdapter.Reports(null));
        assertThrows(NullPointerException.class, () -> new MatchMcpAdapter.Reviews(null));
        assertThrows(NullPointerException.class, () -> new MatchMcpAdapter.Disagreements(null));
    }


    private static MatchDisagreement disagreement() {
        return new MatchDisagreement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "divergence-v1",
                Set.of(DisagreementReason.OVERALL_DELTA), List.of(), DisagreementStatus.LINKED, "JOB-54",
                Instant.EPOCH, Instant.EPOCH);
    }

    private static MatchReport report(UUID profileId, UUID jobId, UUID reportId) {
        return new MatchReport(reportId, profileId, jobId, Instant.EPOCH, Instant.EPOCH, "v1", 40, 40,
                MatchOutcome.WEAK_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 40, 40, EvidenceStatus.MATCH)),
                List.of(), Instant.EPOCH);
    }

    private static MatchReview review(UUID reportId, UUID reviewId) {
        return new MatchReview(reviewId, reportId, "human", "provider-neutral", "v1", 50,
                MatchOutcome.PARTIAL_MATCH, false,
                List.of(new ComponentScore(MatchComponent.TECHNICAL, 20, 40, EvidenceStatus.PARTIAL)),
                List.of(), "score_adjustment", Instant.EPOCH);
    }
}
