package org.instruct.jobenginespring.adapter.in.mcp.match;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.match.MatchAnalysisService;
import org.instruct.jobenginespring.domain.match.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "job-engine.job.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MatchMcpAdapter {
    private final MatchAnalysisService service;
    private final ApplicationExceptionMapper errors = new ApplicationExceptionMapper();
    public MatchMcpAdapter(MatchAnalysisService service) { this.service = service; }

    @McpTool(name="analyze_job_match", description="Create or reuse an immutable deterministic profile-to-job match report.")
    public CallToolResult analyze(@McpToolParam(required=true,description="Profile and job IDs") AnalyzeRequest request) {
        return call(() -> service.analyze(require(request).profileId(), request.jobId()));
    }
    @McpTool(name="analyze_all_job_matches", description="Analyze one bounded page of jobs for a profile; pair failures do not stop the page.")
    public CallToolResult analyzeAll(@McpToolParam(required=true,description="Profile ID and optional page") ProfileRequest request) {
        return call(() -> { var r=require(request); return service.analyzeAll(r.profileId(),r.limit(),r.cursor()); });
    }
    @McpTool(name="get_match_report", description="Get one deterministic report with current source-revision staleness.")
    public CallToolResult getReport(@McpToolParam(required=true,description="Report ID") IdRequest request) { return call(() -> service.getReport(require(request).id())); }
    @McpTool(name="list_match_reports", description="List a bounded page of deterministic reports, optionally filtered by profile and job IDs.")
    public CallToolResult listReports(@McpToolParam(required=true,description="Optional profile/job filters") ReportFilter request) {
        return call(() -> {
            var filter=require(request);
            var page=service.listReports(filter.profileId(),filter.jobId(),filter.limit(),filter.cursor());
            return new Reports(page.items(),page.nextCursor());
        });
    }
    @McpTool(name="submit_match_review", description="Persist a separate advisory review and create a deduplicated disagreement when divergence policy v1 triggers.")
    public CallToolResult submitReview(@McpToolParam(required=true,description="Advisory review") ReviewRequest request) {
        return call(() -> {
            var r = require(request);
            if (r.overallScore() == null || r.blockerMismatch() == null)
                throw new IllegalArgumentException("overallScore and blockerMismatch are required");
            return service.submitReview(new MatchAnalysisService.ReviewDraft(r.reportId(),r.reviewer(),r.model(),
                    r.reviewVersion(),r.overallScore(),r.outcome(),r.blockerMismatch(),r.components(),r.evidence(),r.summary()));
        });
    }
    @McpTool(name="get_match_review", description="Get one advisory review.")
    public CallToolResult getReview(@McpToolParam(required=true,description="Review ID") IdRequest request){return call(() -> service.getReview(require(request).id()));}
    @McpTool(name="list_match_reviews", description="List a bounded page of advisory reviews, optionally filtered by report.")
    public CallToolResult listReviews(@McpToolParam(required=true,description="Optional report filter and page") ReviewFilter request){return call(() -> {var r=require(request);var page=service.listReviews(r.reportId(),r.limit(),r.cursor());return new Reviews(page.items(),page.nextCursor());});}
    @McpTool(name="list_match_disagreements", description="List a bounded page of divergence records, optionally filtered by report.")
    public CallToolResult listDisagreements(@McpToolParam(required=true,description="Optional report filter and page") DisagreementFilter request){return call(() -> {var r=require(request);var page=service.listDisagreements(r.reportId(),r.limit(),r.cursor());return new Disagreements(page.items(),page.nextCursor());});}
    @McpTool(name="acknowledge_match_disagreement", description="Acknowledge a match disagreement without contacting an external provider.")
    public CallToolResult acknowledge(@McpToolParam(required=true,description="Disagreement ID and optional existing Linear issue ID") AcknowledgeRequest request){return call(() -> {var r=require(request);return service.acknowledgeDisagreement(r.disagreementId(),r.linearIssueId());});}
    @McpTool(name="link_match_disagreement", description="Link a match disagreement to an existing Linear issue without contacting an external provider.")
    public CallToolResult link(@McpToolParam(required=true,description="Disagreement ID and existing Linear issue ID") LinkRequest request){return call(() -> {var r=require(request);return service.linkDisagreement(r.disagreementId(),r.linearIssueId());});}

    private CallToolResult call(Supplier<Object> operation){try{return CallToolResult.builder().isError(false).structuredContent(operation.get()).build();}
        catch(Exception e){return CallToolResult.builder().isError(true).structuredContent(errors.toErrorResponse(e)).build();}}
    private static <T>T require(T value){if(value==null)throw new IllegalArgumentException("request must not be null");return value;}
    public record AnalyzeRequest(UUID profileId,UUID jobId){}
    public record ProfileRequest(
            @McpToolParam(required=true,description="Profile UUID") UUID profileId,
            @McpToolParam(required=false,description="Maximum page size, 1-100") Integer limit,
            @McpToolParam(required=false,description="Opaque cursor returned by the previous page") String cursor){
        public ProfileRequest(UUID profileId){this(profileId,null,null);}
    }
    public record IdRequest(UUID id){}
    public record ReportFilter(
            @McpToolParam(required=false,description="Optional profile UUID filter") UUID profileId,
            @McpToolParam(required=false,description="Optional job UUID filter") UUID jobId,
            @McpToolParam(required=false,description="Maximum page size, 1-100") Integer limit,
            @McpToolParam(required=false,description="Opaque cursor returned by the previous page") String cursor){}
    public record ReviewFilter(
            @McpToolParam(required=false,description="Optional report UUID filter") UUID reportId,
            @McpToolParam(required=false,description="Maximum page size, 1-100") Integer limit,
            @McpToolParam(required=false,description="Opaque cursor returned by the previous page") String cursor){
        public ReviewFilter(UUID reportId){this(reportId,null,null);}
    }
    public record DisagreementFilter(
            @McpToolParam(required=false,description="Optional report UUID filter") UUID reportId,
            @McpToolParam(required=false,description="Maximum page size, 1-100") Integer limit,
            @McpToolParam(required=false,description="Opaque cursor returned by the previous page") String cursor){
        public DisagreementFilter(UUID reportId){this(reportId,null,null);}
    }
    public record AcknowledgeRequest(UUID disagreementId,String linearIssueId){}
    public record LinkRequest(UUID disagreementId,String linearIssueId){}
    public record ReviewRequest(UUID reportId,String reviewer,String model,String reviewVersion,Integer overallScore,MatchOutcome outcome,
                                Boolean blockerMismatch,List<ComponentScore> components,List<MatchEvidence> evidence,String summary){}
    public record Reports(List<MatchAnalysisService.ReportView> reports,String nextCursor){public Reports{reports=List.copyOf(reports);}}
    public record Reviews(List<MatchReview> reviews,String nextCursor){public Reviews{reviews=List.copyOf(reviews);}}
    public record Disagreements(List<MatchDisagreement> disagreements,String nextCursor){public Disagreements{disagreements=List.copyOf(disagreements);}}
}
