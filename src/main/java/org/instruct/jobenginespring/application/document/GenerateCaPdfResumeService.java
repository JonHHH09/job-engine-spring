package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeResult;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GenerateProfileResumePdfCommand;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GeneratedProfileResumePdf;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.UUID;

@Service
public class GenerateCaPdfResumeService {

    public static final String CANADIAN_RESUME_TYPE = "canadian_resume";
    private static final String DEFAULT_OUTPUT_DIRECTORY = "tmp/generated-pdfs/canadian-resume";

    private final ProfileResumePdfGenerationWorkflow workflow;
    private final Path outputDirectory;

    @Autowired
    public GenerateCaPdfResumeService(
            ProfileResumePdfGenerationWorkflow workflow,
            @Value("${job-engine.pdf-generation.canadian-resume-output-dir:" + DEFAULT_OUTPUT_DIRECTORY + "}") String outputDirectory
    ) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow must not be null");
        this.outputDirectory = GeneratePdfResumeService.toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    GenerateCaPdfResumeService(ProfileResumePdfGenerationWorkflow workflow, Path outputDirectory) {
        this.workflow = java.util.Objects.requireNonNull(workflow, "workflow must not be null");
        this.outputDirectory = java.util.Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    }

    @Transactional
    public GeneratePdfResumeResult generateCanadianPdfResume(GenerateCaPdfResumeRequest request) {
        GenerateCaPdfResumeRequest safeRequest = java.util.Objects.requireNonNull(request, "request must not be null");
        UUID profileId = safeRequest.profileId();
        ProfileAggregate aggregate = workflow.requireProfile(profileId);

        // This service owns only the Canadian variant decisions: resume type, output folder,
        // file naming, title, and Canadian section rendering. The shared workflow performs
        // reusable profile-linked PDF/document persistence for every resume variant.
        GeneratedProfileResumePdf generated = workflow.generateAndLink(new GenerateProfileResumePdfCommand(
                profileId,
                CANADIAN_RESUME_TYPE,
                outputDirectory,
                canadianResumeFileName(profileId),
                aggregate.profile().fullName(),
                ResumeBodyRenderer.renderCanadianResume(aggregate)
        ));
        return GeneratePdfResumeResult.from(generated);
    }

    private static String canadianResumeFileName(UUID profileId) {
        return "canadian-resume-" + profileId + ".pdf";
    }

    public record GenerateCaPdfResumeRequest(UUID profileId) {
    }
}
