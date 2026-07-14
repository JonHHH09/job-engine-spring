package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeResult;
import org.instruct.jobenginespring.application.document.PdfGenerationService.PageNumberLocale;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GenerateProfileResumePdfCommand;
import org.instruct.jobenginespring.application.document.ProfileResumePdfGenerationWorkflow.GeneratedProfileResumePdf;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.resume.OfflineFrenchResumeTranslator;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class GenerateCanadianFrenchPdfResumeService {

    public static final String CANADIAN_FRENCH_RESUME_TYPE = "canadian_resume_fr";
    private static final String DEFAULT_OUTPUT_DIRECTORY = "tmp/generated-pdfs/canadian-resume-fr";

    private final ProfileResumePdfGenerationWorkflow workflow;
    private final OfflineFrenchResumeTranslator translator;
    private final Path outputDirectory;

    @Autowired
    public GenerateCanadianFrenchPdfResumeService(
            ProfileResumePdfGenerationWorkflow workflow,
            OfflineFrenchResumeTranslator translator,
            @Value("${job-engine.pdf-generation.canadian-french-resume-output-dir:" + DEFAULT_OUTPUT_DIRECTORY + "}")
            String outputDirectory
    ) {
        this.workflow = Objects.requireNonNull(workflow, "workflow must not be null");
        this.translator = Objects.requireNonNull(translator, "translator must not be null");
        this.outputDirectory = GeneratePdfResumeService.toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    GenerateCanadianFrenchPdfResumeService(
            ProfileResumePdfGenerationWorkflow workflow,
            OfflineFrenchResumeTranslator translator,
            Path outputDirectory
    ) {
        this.workflow = Objects.requireNonNull(workflow, "workflow must not be null");
        this.translator = Objects.requireNonNull(translator, "translator must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
    }

    @Transactional
    public GeneratePdfResumeResult generateCanadianFrenchPdfResume(GenerateCanadianFrenchPdfResumeRequest request) {
        GenerateCanadianFrenchPdfResumeRequest safeRequest = Objects.requireNonNull(request, "request must not be null");
        UUID profileId = safeRequest.profileId();
        ProfileAggregate aggregate = workflow.requireProfile(profileId);
        String title = aggregate.profile().fullName();
        String body = ResumeBodyRenderer.renderCanadianFrenchResume(aggregate, translator);
        validatePdfCompatibleText("title", title);
        validatePdfCompatibleText("body", body);
        GeneratedProfileResumePdf generated = workflow.generateAndLink(new GenerateProfileResumePdfCommand(
                profileId,
                CANADIAN_FRENCH_RESUME_TYPE,
                outputDirectory,
                frenchCanadianResumeFileName(profileId),
                title,
                body,
                PageNumberLocale.CANADIAN_FRENCH
        ));
        return GeneratePdfResumeResult.from(generated);
    }

    private static String frenchCanadianResumeFileName(UUID profileId) {
        return "canadian-resume-fr-" + profileId + ".pdf";
    }

    private static void validatePdfCompatibleText(String field, String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            boolean printableAscii = character >= 32 && character <= 126;
            boolean printableLatinOne = character >= 160 && character <= 255;
            if (!Character.isWhitespace(character) && !printableAscii && !printableLatinOne) {
                throw new ApplicationException(
                        ApplicationErrorCode.VALIDATION_ERROR,
                        "French Canadian resume contains characters unsupported by the current PDF font",
                        Map.of("field", field, "reason", "contains unsupported characters"),
                        null
                );
            }
        }
    }

    public record GenerateCanadianFrenchPdfResumeRequest(UUID profileId) {
    }
}
