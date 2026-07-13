package org.instruct.jobenginespring.adapter.in.mcp.resume;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.document.GenerateGermanTailoredResumeService;
import org.instruct.jobenginespring.application.document.GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeRequest;
import org.instruct.jobenginespring.application.document.GenerateGermanTailoredResumeService.GenerateGermanTailoredResumeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GermanTailoredResumeMcpTests {

    private final GenerateGermanTailoredResumeService service = mock(GenerateGermanTailoredResumeService.class);
    private final GermanTailoredResumeMcp adapter = new GermanTailoredResumeMcp(service);

    @Test
    void returnsStructuredSuccess() {
        UUID profileId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        GenerateGermanTailoredResumeResult result = new GenerateGermanTailoredResumeResult(
                UUID.randomUUID(), profileId, jobId, "germany", List.of(), false, "now"
        );
        when(service.generate(any())).thenReturn(result);

        CallToolResult response = adapter.generateGermanTailoredResume(new GenerateGermanTailoredResumeRequest(profileId, jobId));

        assertFalse(response.isError());
        assertEquals(result, response.structuredContent());
    }

    @Test
    void mapsApplicationErrors() {
        when(service.generate(any())).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid German tailored resume request",
                Map.of("field", "profileId", "reason", "must not be null"),
                null
        ));

        CallToolResult response = adapter.generateGermanTailoredResume(
                new GenerateGermanTailoredResumeRequest(null, UUID.randomUUID())
        );

        assertTrue(response.isError());
    }
}
