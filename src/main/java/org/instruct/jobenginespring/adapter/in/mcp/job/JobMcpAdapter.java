package org.instruct.jobenginespring.adapter.in.mcp.job;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.job.JobAnalysisService;
import org.instruct.jobenginespring.application.job.JobService;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class JobMcpAdapter {

    private final JobService jobService;
    private final JobAnalysisService jobAnalysisService;
    private final ApplicationExceptionMapper exceptionMapper = new ApplicationExceptionMapper();

    @Autowired
    public JobMcpAdapter(JobService jobService, JobAnalysisService jobAnalysisService) {
        this.jobService = Objects.requireNonNull(jobService, "jobService must not be null");
        this.jobAnalysisService = Objects.requireNonNull(jobAnalysisService, "jobAnalysisService must not be null");
    }

    @McpTool(
            name = "list_jobs",
            description = "List a bounded page of stored job postings without returning source ingestion raw text."
    )
    public CallToolResult listJobs(
            @McpToolParam(required = false, description = "Optional page size and cursor from the previous response")
            ListRequest request
    ) {
        return call(() -> {
            var safeRequest = request == null ? new ListRequest(null, null) : request;
            var page = jobService.listJobs(safeRequest.limit(), safeRequest.cursor());
            return new ListJobsResult(page.items(), page.nextCursor());
        });
    }

    @McpTool(
            name = "get_job",
            description = "Get a stored job aggregate by job UUID, including normalized skills and insertion provenance."
    )
    public CallToolResult getJob(
            @McpToolParam(required = true, description = "Job UUID") UUID jobId
    ) {
        return call(() -> jobService.getJob(jobId).orElseThrow(() -> new JobService.JobNotFoundException(jobId)));
    }

    @McpTool(
            name = "search_jobs",
            description = "Search stored jobs by title, company, location, description, experience requirement, seniority, employment type, and required skills."
    )
    public CallToolResult searchJobs(
            @McpToolParam(required = true, description = "Job search query and optional result limit")
            JobSearchRequest request
    ) {
        return call(() -> jobService.searchJobs(request == null ? null : request.toServiceRequest()));
    }

    @McpTool(
            name = "update_job",
            description = "Partially update a stored job. Omitted fields preserve existing values; provided skills replace existing skills."
    )
    public CallToolResult updateJob(
            @McpToolParam(required = true, description = "Job partial update request")
            UpdateJobRequest request
    ) {
        return call(() -> jobService.updateJob(request == null ? null : request.toServiceRequest()));
    }

    @McpTool(
            name = "delete_job",
            description = "Hard delete a stored job by UUID."
    )
    public CallToolResult deleteJob(
            @McpToolParam(required = true, description = "Job UUID") UUID jobId
    ) {
        return call(() -> jobService.deleteJob(jobId));
    }

    @McpTool(
            name = "add_job_from_text",
            description = "Insert a job from pasted text and optional structured fields. The text is hashed for idempotent source tracking."
    )
    public CallToolResult addJobFromText(
            @McpToolParam(required = true, description = "Job text plus optional normalized fields")
            AddJobFromTextRequest request
    ) {
        return call(() -> jobService.addJobFromText(request == null ? null : request.toServiceRequest()));
    }

    @McpTool(
            name = "add_job_from_link",
            description = "Insert a job from a URL by fetching the link and combining extracted page text with optional structured fields. The full retrieval URL is used only for the fetch; persisted and MCP-visible provenance is redacted."
    )
    public CallToolResult addJobFromLink(
            @McpToolParam(required = true, description = "Job URL plus optional normalized fields")
            AddJobFromLinkRequest request
    ) {
        return call(() -> jobService.addJobFromLink(request == null ? null : request.toServiceRequest()));
    }

    @McpTool(
            name = "analyze_job_link",
            description = "Fetch a job URL, run configured Hermes analysis, persist only redacted provenance plus the structured Hermes response, and return an analysis-run report without creating a job."
    )
    public CallToolResult analyzeJobLink(
            @McpToolParam(required = true, description = "Job URL analysis request")
            AnalyzeJobLinkRequest request
    ) {
        return call(() -> jobAnalysisService.analyzeJobLink(request == null ? null : request.toServiceRequest()));
    }

    @McpTool(
            name = "add_job_from_analysis",
            description = "Create or reuse a normalized job by reading a previously stored Hermes analysis run."
    )
    public CallToolResult addJobFromAnalysis(
            @McpToolParam(required = true, description = "Stored job analysis run identifier")
            AddJobFromAnalysisRequest request
    ) {
        return call(() -> jobAnalysisService.addJobFromAnalysis(request == null ? null : request.toServiceRequest()));
    }

    private CallToolResult call(Supplier<Object> operation) {
        try {
            return CallToolResult.builder()
                    .isError(false)
                    .structuredContent(operation.get())
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder()
                    .isError(true)
                    .structuredContent(exceptionMapper.toErrorResponse(exception))
                    .build();
        }
    }

    public record ListRequest(
            @McpToolParam(required = false, description = "Maximum page size, 1-100") Integer limit,
            @McpToolParam(required = false, description = "Opaque cursor returned by the previous page") String cursor
    ) {
    }

    public record ListJobsResult(List<JobPosting> jobs, String nextCursor) {
        public ListJobsResult {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }
    }

    public record JobSearchRequest(
            @McpToolParam(required = true, description = "Search query, maximum 256 characters and 16 searchable terms") String query,
            @McpToolParam(required = false, description = "Maximum number of results, 1-100") Integer limit
    ) {
        JobService.JobSearchRequest toServiceRequest() {
            return new JobService.JobSearchRequest(query, limit);
        }
    }

    public record UpdateJobRequest(
            @McpToolParam(required = true, description = "Job UUID") UUID jobId,
            @McpToolParam(required = false, description = "Source label") String sourceLabel,
            @McpToolParam(required = false, description = "Normalized job title") String title,
            @McpToolParam(required = false, description = "Company name") String company,
            @McpToolParam(required = false, description = "Job location") String location,
            @McpToolParam(required = false, description = "Job description") String description,
            @McpToolParam(required = false, description = "Required skills. When provided, replaces existing skills.") List<String> skills,
            @McpToolParam(required = false, description = "Experience requirement") String experienceRequirement,
            @McpToolParam(required = false, description = "Employment type") String employmentType,
            @McpToolParam(required = false, description = "Seniority") String seniority,
            @McpToolParam(required = false, description = "Posting timestamp") Instant postedAt
    ) {
        JobService.UpdateJobRequest toServiceRequest() {
            return new JobService.UpdateJobRequest(jobId, sourceLabel, title, company, location, description, skills,
                    experienceRequirement, employmentType, seniority, postedAt);
        }
    }

    public record AddJobFromTextRequest(
            @McpToolParam(required = true, description = "Raw pasted job text") String text,
            @McpToolParam(required = false, description = "Source label such as manual paste or site name") String sourceLabel,
            @McpToolParam(required = false, description = "Normalized job title override") String title,
            @McpToolParam(required = false, description = "Company name") String company,
            @McpToolParam(required = false, description = "Job location") String location,
            @McpToolParam(required = false, description = "Job description override") String description,
            @McpToolParam(required = false, description = "Required skills") List<String> skills,
            @McpToolParam(required = false, description = "Experience requirement") String experienceRequirement,
            @McpToolParam(required = false, description = "Employment type") String employmentType,
            @McpToolParam(required = false, description = "Seniority") String seniority,
            @McpToolParam(required = false, description = "Posting timestamp") Instant postedAt
    ) {
        JobService.AddJobFromTextRequest toServiceRequest() {
            return new JobService.AddJobFromTextRequest(text, sourceLabel, title, company, location, description, skills,
                    experienceRequirement, employmentType, seniority, postedAt);
        }
    }

    public record AddJobFromLinkRequest(
            @McpToolParam(required = true, description = "Job URL") String url,
            @McpToolParam(required = false, description = "Source label such as job board name") String sourceLabel,
            @McpToolParam(required = false, description = "Normalized job title override") String title,
            @McpToolParam(required = false, description = "Company name") String company,
            @McpToolParam(required = false, description = "Job location") String location,
            @McpToolParam(required = false, description = "Job description override") String description,
            @McpToolParam(required = false, description = "Required skills") List<String> skills,
            @McpToolParam(required = false, description = "Experience requirement") String experienceRequirement,
            @McpToolParam(required = false, description = "Employment type") String employmentType,
            @McpToolParam(required = false, description = "Seniority") String seniority,
            @McpToolParam(required = false, description = "Posting timestamp") Instant postedAt
    ) {
        JobService.AddJobFromLinkRequest toServiceRequest() {
            return new JobService.AddJobFromLinkRequest(url, sourceLabel, title, company, location, description, skills,
                    experienceRequirement, employmentType, seniority, postedAt);
        }
    }

    public record AnalyzeJobLinkRequest(
            @McpToolParam(required = true, description = "Job URL to fetch and analyze") String url
    ) {
        JobAnalysisService.AnalyzeJobLinkRequest toServiceRequest() {
            return new JobAnalysisService.AnalyzeJobLinkRequest(url);
        }
    }

    public record AddJobFromAnalysisRequest(
            @McpToolParam(required = true, description = "Stored job analysis run UUID") UUID analysisRunId
    ) {
        JobAnalysisService.AddJobFromAnalysisRequest toServiceRequest() {
            return new JobAnalysisService.AddJobFromAnalysisRequest(analysisRunId);
        }
    }
}
