package org.instruct.jobenginespring.adapter.in.mcp.jobscan;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationExceptionMapper;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobScanService;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ArbeitnowJobScanMcpAdapter {
    private final ArbeitnowJobScanService service;
    private final ArbeitnowJobImportService importService;
    private final ApplicationExceptionMapper errors = new ApplicationExceptionMapper();
    @Autowired
    public ArbeitnowJobScanMcpAdapter(ArbeitnowJobScanService service, ArbeitnowJobImportService importService) { this.service = service; this.importService = importService; }
    ArbeitnowJobScanMcpAdapter(ArbeitnowJobScanService service) { this(service, null); }
    @McpTool(name = "scan_arbeitnow_jobs", description = "Read-only scan of the Arbeitnow public job-board API. Results are mutable and are not a snapshot.")
    public CallToolResult scan(@McpToolParam(required = false, description = "Optional query, filters, bounded scan controls, and opaque continuation cursor") Request request) {
        try { Request r=request==null?new Request(null,null,null,null,null,null,null,null,null):request; return CallToolResult.builder().isError(false).structuredContent(service.scan(new ArbeitnowJobScanService.ScanRequest(r.query(),r.location(),r.remoteOnly(),r.visaSponsorship(),r.tags(),r.jobTypes(),r.limit(),r.maxPages(),r.cursor()))).build(); }
        catch(Exception exception) { return CallToolResult.builder().isError(true).structuredContent(errors.toErrorResponse(exception)).build(); }
    }
    @McpTool(name = "import_arbeitnow_job", description = "Import one signed, short-lived Arbeitnow scan candidate without refetching it.")
    public CallToolResult importJob(@McpToolParam(description = "Signed candidate token returned by scan_arbeitnow_jobs") ImportRequest request) {
        try {
            if (request == null || request.candidateToken() == null || request.candidateToken().isBlank()) throw new IllegalArgumentException("candidateToken is required");
            return CallToolResult.builder().isError(false).structuredContent(importService.importCandidate(request.candidateToken())).build();
        } catch(Exception exception) { return CallToolResult.builder().isError(true).structuredContent(errors.toErrorResponse(exception)).build(); }
    }
    public record Request(@McpToolParam(required=false,description="Case-insensitive all-term query") String query, @McpToolParam(required=false,description="Location substring") String location, @McpToolParam(required=false,description="Only remote jobs") Boolean remoteOnly, @McpToolParam(required=false,description="Upstream documented visa_sponsorship filter") Boolean visaSponsorship, @McpToolParam(required=false,description="Requested tags; any matches") List<String> tags, @McpToolParam(required=false,description="Requested job types; any matches") List<String> jobTypes, @McpToolParam(required=false,description="Result limit 1-100") Integer limit, @McpToolParam(required=false,description="API pages 1-5") Integer maxPages, @McpToolParam(required=false,description="Opaque continuation cursor") String cursor) {}
    public record ImportRequest(@McpToolParam(description="Signed candidate token") String candidateToken) {}
}
