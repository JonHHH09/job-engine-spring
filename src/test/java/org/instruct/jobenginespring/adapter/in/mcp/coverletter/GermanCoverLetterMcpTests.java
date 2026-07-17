package org.instruct.jobenginespring.adapter.in.mcp.coverletter;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.instruct.jobenginespring.application.document.GenerateGermanCoverLetterService;
import org.instruct.jobenginespring.application.document.GenerateGermanCoverLetterService.GenerateGermanCoverLetterRequest;
import org.instruct.jobenginespring.application.document.GenerateGermanCoverLetterService.GenerateGermanCoverLetterResult;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GermanCoverLetterMcpTests {

    private final GenerateGermanCoverLetterService service = mock(GenerateGermanCoverLetterService.class);
    private final GermanCoverLetterMcp adapter = new GermanCoverLetterMcp(service);

    @Test
    void returnsMetadataOnlyStructuredSuccess() {
        UUID profileId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        GenerateGermanCoverLetterResult result = new GenerateGermanCoverLetterResult(
                UUID.randomUUID(), profileId, jobId, resumeId, UUID.randomUUID(), "germany", "de",
                UUID.randomUUID(), "tmp/generated-pdfs/german-cover-letter/letter.pdf", 1234L, 1, false, "now"
        );
        when(service.generate(any())).thenReturn(result);

        CallToolResult response = adapter.generateGermanCoverLetter(new GenerateGermanCoverLetterRequest(profileId, jobId, resumeId));

        assertFalse(response.isError());
        assertEquals(result, response.structuredContent());
        assertTrue(response.structuredContent().toString().contains("letter.pdf"));
        assertFalse(response.structuredContent().toString().contains("paragraph"));
    }

    @Test
    void mapsFailuresToStructuredMcpErrors() {
        when(service.generate(any())).thenThrow(new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR, "Invalid cover-letter request",
                java.util.Map.of("field", "resumeId"), null
        ));

        CallToolResult response = adapter.generateGermanCoverLetter(
                new GenerateGermanCoverLetterRequest(UUID.randomUUID(), UUID.randomUUID(), null)
        );

        assertTrue(response.isError());
        assertTrue(response.structuredContent().toString().contains("validation_error"));
    }
}
