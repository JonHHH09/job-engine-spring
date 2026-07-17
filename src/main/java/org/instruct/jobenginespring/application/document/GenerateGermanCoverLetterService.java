package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.coverletter.GermanCoverLetterContent;
import org.instruct.jobenginespring.application.coverletter.GermanCoverLetterContentBuilder;
import org.instruct.jobenginespring.application.coverletter.GermanCoverLetterContentReview;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.CoverLetterAggregateWrite;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.ReplaceResult;
import org.instruct.jobenginespring.application.coverletter.port.CoverLetterRepository.VariantWrite;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobService.JobNotFoundException;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.domain.coverletter.CoverLetter;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterParagraph;
import org.instruct.jobenginespring.domain.coverletter.CoverLetterVariant;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Generates and persists one Germany-format cover-letter variant for an exact tailored resume. */
@Service
public class GenerateGermanCoverLetterService {

    private static final String DEFAULT_OUTPUT_DIRECTORY = "tmp/generated-pdfs/german-cover-letter";

    private final ProfileRepository profileRepository;
    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final GermanCoverLetterPersistenceService persistenceService;
    private final DocumentStorageService documentStorageService;
    private final GeneratedResumeCleanupService cleanupService;
    private final GermanCoverLetterContentBuilder contentBuilder;
    private final Path outputDirectory;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GenerateGermanCoverLetterService(
            ProfileRepository profileRepository,
            JobRepository jobRepository,
            ResumeRepository resumeRepository,
            GermanCoverLetterPersistenceService persistenceService,
            DocumentStorageService documentStorageService,
            GeneratedResumeCleanupService cleanupService,
            @Value("${job-engine.pdf-generation.german-cover-letter-output-dir:" + DEFAULT_OUTPUT_DIRECTORY + "}") String outputDirectory
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository must not be null");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.contentBuilder = new GermanCoverLetterContentBuilder();
        this.outputDirectory = GeneratePdfResumeService.toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    GenerateGermanCoverLetterService(
            ProfileRepository profileRepository,
            JobRepository jobRepository,
            ResumeRepository resumeRepository,
            GermanCoverLetterPersistenceService persistenceService,
            DocumentStorageService documentStorageService,
            GeneratedResumeCleanupService cleanupService,
            Path outputDirectory,
            Clock clock
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository must not be null");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.contentBuilder = new GermanCoverLetterContentBuilder();
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public GenerateGermanCoverLetterResult generate(GenerateGermanCoverLetterRequest request) {
        GenerateGermanCoverLetterRequest safe = Objects.requireNonNull(request, "request must not be null");
        UUID profileId = requireId(safe.profileId(), "profileId");
        UUID jobId = requireId(safe.jobId(), "jobId");
        UUID resumeId = requireId(safe.resumeId(), "resumeId");

        ProfileAggregate profile = profileRepository.findProfileAggregate(profileId)
                .orElseThrow(() -> new ProfileNotFoundException(profileId));
        JobAggregate job = jobRepository.findJobAggregate(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> missingResume(resumeId));
        validateResumeLinkage(resume, profile, job);

        GermanCoverLetterContent content = contentBuilder.build(profile, job);
        GermanCoverLetterContentReview.review(content);
        GeneratedPdfAssets pdf = generatePdf(content, profile.profile().id());
        try {
            Instant now = clock.instant();
            CoverLetter coverLetter = new CoverLetter(
                    UUID.randomUUID(), profileId, jobId, resumeId,
                    profile.profile().updatedAt(), job.job().updatedAt(), resume.updatedAt(), now, now
            );
            CoverLetterVariant variant = new CoverLetterVariant(
                    UUID.randomUUID(), coverLetter.id(), CoverLetterVariant.FORMAT_GERMANY, CoverLetterVariant.LANGUAGE_DE,
                    pdf.document().id(), pdf.generatedFile().path(), content.subject(), content.salutation(),
                    content.closing(), content.signature(), now, now
            );
            List<CoverLetterParagraph> paragraphs = new ArrayList<>();
            for (int index = 0; index < content.paragraphs().size(); index++) {
                paragraphs.add(new CoverLetterParagraph(UUID.randomUUID(), variant.id(), index, content.paragraphs().get(index)));
            }
            ReplaceResult replaced = persistenceService.replace(
                    new CoverLetterAggregateWrite(coverLetter, new VariantWrite(variant, paragraphs)),
                    new GermanCoverLetterPersistenceService.GeneratedAsset(pdf.document().id(), pdf.generatedFile().path())
            );
            return new GenerateGermanCoverLetterResult(
                    coverLetter.id(), profileId, jobId, resumeId, variant.id(),
                    variant.format(), variant.language(), variant.documentId(), variant.filePath(),
                    pdf.generatedFile().byteSize(), pdf.generatedFile().pageCount(),
                    !replaced.previousVariants().isEmpty(), now.toString()
            );
        } catch (RuntimeException | Error failure) {
            discard(pdf, failure);
            throw failure;
        }
    }

    private GeneratedPdfAssets generatePdf(GermanCoverLetterContent content, UUID profileId) {
        String fileName = buildFileName(content.senderName(), profileId);
        PdfGenerationService.GeneratedPdfFileResult generatedFile = new GermanCoverLetterPdfRenderer(outputDirectory)
                .generate(fileName, content);
        try {
            StoredDocumentMetadata document = documentStorageService.storeGeneratedDocumentFile(
                    Path.of(generatedFile.path()), DocumentStorageService.PDF_MEDIA_TYPE
            );
            return new GeneratedPdfAssets(document, generatedFile);
        } catch (RuntimeException failure) {
            enqueueCleanup(generatedFile.path(), failure);
            throw failure;
        }
    }

    private void discard(GeneratedPdfAssets assets, Throwable failure) {
        try {
            cleanupService.enqueueAfterCompletion(assets.document().id(), assets.generatedFile().path());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void enqueueCleanup(String filePath, Throwable failure) {
        try {
            cleanupService.enqueueAfterCompletion(filePath);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void validateResumeLinkage(Resume resume, ProfileAggregate profile, JobAggregate job) {
        UUID profileId = profile.profile().id();
        UUID jobId = job.job().id();
        if (!profileId.equals(resume.profileId()) || !jobId.equals(resume.jobId())) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Selected resume is not linked to the supplied profile and job",
                    Map.of("field", "resumeId", "reason", "must belong to profileId and jobId"),
                    null
            );
        }
        if (!Resume.FORMAT_GERMANY.equals(resume.format())) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Selected resume is not a Germany-format resume",
                    Map.of("field", "resumeId", "reason", "format must be germany"),
                    null
            );
        }
        if (!profile.profile().updatedAt().equals(resume.profileRevision())
                || !job.job().updatedAt().equals(resume.jobRevision())) {
            throw new ApplicationException(
                    ApplicationErrorCode.CONFLICT,
                    "Selected resume is stale",
                    Map.of("field", "resumeId", "reason", "regenerate the resume from the current profile and job"),
                    null
            );
        }
    }

    private static UUID requireId(UUID id, String field) {
        if (id == null) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Invalid German cover-letter request",
                    Map.of("field", field, "reason", "must not be null"),
                    null
            );
        }
        return id;
    }

    static String buildFileName(String fullName, UUID profileId) {
        String candidate = slugify(fullName);
        String profileShort = profileId.toString().replace("-", "").substring(0, 8);
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "germany_cover_letter_" + candidate + "_" + profileShort + "_" + unique + ".pdf";
    }

    static String slugify(String value) {
        String raw = value == null ? "candidate" : value.strip().toLowerCase(Locale.ROOT);
        String slug = raw.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            return "candidate";
        }
        return slug.length() > 40 ? slug.substring(0, 40).replaceAll("-+$", "") : slug;
    }

    private static ApplicationException missingResume(UUID resumeId) {
        return new ApplicationException(
                ApplicationErrorCode.NOT_FOUND,
                "Selected resume was not found",
                Map.of("resumeId", String.valueOf(resumeId)),
                null
        );
    }

    public record GenerateGermanCoverLetterRequest(UUID profileId, UUID jobId, UUID resumeId) {
    }

    public record GenerateGermanCoverLetterResult(
            UUID coverLetterId,
            UUID profileId,
            UUID jobId,
            UUID resumeId,
            UUID variantId,
            String format,
            String language,
            UUID documentId,
            String filePath,
            long byteSize,
            int pageCount,
            boolean replacedExisting,
            String generatedAt
    ) {
    }

    private record GeneratedPdfAssets(StoredDocumentMetadata document, PdfGenerationService.GeneratedPdfFileResult generatedFile) {
    }
}
