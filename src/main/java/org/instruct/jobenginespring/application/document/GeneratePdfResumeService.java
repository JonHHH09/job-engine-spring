package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GenerateProfileResumePdfCommand;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GeneratedProfileResumePdf;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.application.security.McpAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class GeneratePdfResumeService {

    public static final String MASTER_RESUME_TYPE = "master_resume";
    private static final String DEFAULT_OUTPUT_DIRECTORY = "tmp/generated-pdfs/master-resume";

    private final ProfileResumePdfGenerationWorkflow workflow;
    private final McpAccessPolicy accessPolicy;
    private final Path outputDirectory;

    @Autowired
    public GeneratePdfResumeService(
            ProfileResumePdfGenerationWorkflow workflow,
            McpAccessPolicy accessPolicy,
            @Value("${job-engine.pdf-generation.master-resume-output-dir:" + DEFAULT_OUTPUT_DIRECTORY + "}") String outputDirectory
    ) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow must not be null");
        this.accessPolicy = java.util.Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.outputDirectory = toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    GeneratePdfResumeService(ProfileResumePdfGenerationWorkflow workflow, Path outputDirectory) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow must not be null");
        this.accessPolicy = McpAccessPolicy.permitAllForTests();
        this.outputDirectory = java.util.Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    }

    GeneratePdfResumeService(ProfileResumePdfGenerationWorkflow workflow, String outputDirectory) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow must not be null");
        this.accessPolicy = McpAccessPolicy.permitAllForTests();
        this.outputDirectory = toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    @Transactional
    public GeneratePdfResumeResult generatePdfResume(GeneratePdfResumeRequest request) {
        GeneratePdfResumeRequest safeRequest = java.util.Objects.requireNonNull(request, "request must not be null");
        accessPolicy.authorize(safeRequest.accessToken(), "generate_pdf_resume");
        UUID profileId = safeRequest.profileId();
        ProfileAggregate aggregate = workflow.requireProfile(profileId);
        GeneratedProfileResumePdf generated = workflow.generateAndLink(new GenerateProfileResumePdfCommand(
                profileId,
                MASTER_RESUME_TYPE,
                outputDirectory,
                masterResumeFileName(profileId),
                aggregate.profile().fullName() + " - Master Resume",
                ResumeBodyRenderer.renderMasterResume(aggregate)
        ));
        return GeneratePdfResumeResult.from(generated);
    }

    static Path toPath(String rawPath, String defaultPath) {
        try {
            return Path.of(rawPath == null || rawPath.isBlank() ? defaultPath : rawPath);
        } catch (InvalidPathException exception) {
            throw new org.instruct.jobenginespring.application.error.ApplicationException(
                    org.instruct.jobenginespring.application.error.ApplicationErrorCode.VALIDATION_ERROR,
                    "Invalid resume PDF generation configuration",
                    java.util.Map.of("field", "outputDirectory", "reason", "must be a valid file path"),
                    exception
            );
        }
    }

    private static String masterResumeFileName(UUID profileId) {
        return "master-resume-" + profileId + ".pdf";
    }

    public record GeneratePdfResumeRequest(UUID profileId, String accessToken) {
        public GeneratePdfResumeRequest(UUID profileId) {
            this(profileId, null);
        }
    }

    public record GeneratePdfResumeResult(
            UUID profileId,
            UUID resumeLinkId,
            UUID documentId,
            String filePath,
            String resumeType,
            StoredDocumentMetadata document,
            GeneratedPdfFileResult generatedFile,
            boolean replacedExistingLink,
            String linkedAt
    ) {
        static GeneratePdfResumeResult from(GeneratedProfileResumePdf generated) {
            return new GeneratePdfResumeResult(
                    generated.profileId(),
                    generated.resumeLinkId(),
                    generated.documentId(),
                    generated.filePath(),
                    generated.resumeType(),
                    generated.document(),
                    generated.generatedFile(),
                    generated.replacedExistingLink(),
                    generated.linkedAt()
            );
        }
    }
}
