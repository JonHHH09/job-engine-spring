package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.PdfGenerationService.GeneratedPdfFileResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.document.port.GeneratedResumeFileRepository;
import org.instruct.jobenginespring.application.document.port.TransactionLifecycle;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.JobService.JobNotFoundException;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException;
import org.instruct.jobenginespring.application.profile.port.ProfilePersonalDetailsRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.resume.GermanLebenslaufBodyRenderer;
import org.instruct.jobenginespring.application.resume.GermanLebenslaufContentBuilder;
import org.instruct.jobenginespring.application.resume.OfflineGermanResumeTranslator;
import org.instruct.jobenginespring.application.resume.StructuredResumeContent;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.EntryWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ReplaceResult;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.ResumeAggregateWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.SectionWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.VariantWrite;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;
import org.instruct.jobenginespring.domain.resume.Resume;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class GenerateGermanTailoredResumeService {

    public static final String FORMAT_GERMANY = Resume.FORMAT_GERMANY;
    private static final String DEFAULT_OUTPUT_DIRECTORY = "tmp/generated-pdfs/german-resume";

    private final ProfileRepository profileRepository;
    private final JobRepository jobRepository;
    private final ProfilePersonalDetailsRepository personalDetailsRepository;
    private final ResumeRepository resumeRepository;
    private final DocumentStorageService documentStorageService;
    private final DocumentRepository documentRepository;
    private final GeneratedResumeFileRepository fileRepository;
    private final GeneratedResumeCleanupService cleanupService;
    private final TransactionLifecycle transactionLifecycle;
    private final OfflineGermanResumeTranslator translator;
    private final Path outputDirectory;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public GenerateGermanTailoredResumeService(
            ProfileRepository profileRepository,
            JobRepository jobRepository,
            ProfilePersonalDetailsRepository personalDetailsRepository,
            ResumeRepository resumeRepository,
            DocumentStorageService documentStorageService,
            DocumentRepository documentRepository,
            GeneratedResumeFileRepository fileRepository,
            GeneratedResumeCleanupService cleanupService,
            TransactionLifecycle transactionLifecycle,
            OfflineGermanResumeTranslator translator,
            @Value("${job-engine.pdf-generation.german-resume-output-dir:" + DEFAULT_OUTPUT_DIRECTORY + "}") String outputDirectory
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.personalDetailsRepository = Objects.requireNonNull(personalDetailsRepository, "personalDetailsRepository must not be null");
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.translator = Objects.requireNonNull(translator, "translator must not be null");
        this.outputDirectory = GeneratePdfResumeService.toPath(outputDirectory, DEFAULT_OUTPUT_DIRECTORY);
    }

    GenerateGermanTailoredResumeService(
            ProfileRepository profileRepository,
            JobRepository jobRepository,
            ProfilePersonalDetailsRepository personalDetailsRepository,
            ResumeRepository resumeRepository,
            DocumentStorageService documentStorageService,
            DocumentRepository documentRepository,
            GeneratedResumeFileRepository fileRepository,
            GeneratedResumeCleanupService cleanupService,
            TransactionLifecycle transactionLifecycle,
            OfflineGermanResumeTranslator translator,
            Path outputDirectory,
            Clock clock
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.personalDetailsRepository = Objects.requireNonNull(personalDetailsRepository, "personalDetailsRepository must not be null");
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository must not be null");
        this.documentStorageService = Objects.requireNonNull(documentStorageService, "documentStorageService must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService must not be null");
        this.transactionLifecycle = Objects.requireNonNull(transactionLifecycle, "transactionLifecycle must not be null");
        this.translator = Objects.requireNonNull(translator, "translator must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public GenerateGermanTailoredResumeResult generate(GenerateGermanTailoredResumeRequest request) {
        GenerateGermanTailoredResumeRequest safe = Objects.requireNonNull(request, "request must not be null");
        UUID profileId = requireId(safe.profileId(), "profileId");
        UUID jobId = requireId(safe.jobId(), "jobId");

        ProfileAggregate profile = profileRepository.findProfileAggregate(profileId)
                .orElseThrow(() -> new ProfileNotFoundException(profileId));
        JobAggregate job = jobRepository.findJobAggregate(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        ProfilePersonalDetails personalDetails = personalDetailsRepository.findByProfileId(profileId).orElse(null);

        StructuredResumeContent english = GermanLebenslaufContentBuilder.buildEnglish(profile, job, personalDetails);
        StructuredResumeContent german = translator.toGerman(english);

        GeneratedPdfAssets englishPdf = generatePdf(english, profileId, jobId, ResumeVariant.LANGUAGE_EN);
        GeneratedPdfAssets germanPdf = null;
        try {
            germanPdf = generatePdf(german, profileId, jobId, ResumeVariant.LANGUAGE_DE);
            Instant now = clock.instant();
            UUID resumeId = UUID.randomUUID();
            Resume resume = new Resume(
                    resumeId,
                    profileId,
                    jobId,
                    FORMAT_GERMANY,
                    profile.profile().updatedAt(),
                    job.job().updatedAt(),
                    now,
                    now
            );

            ResumeVariant enVariant = new ResumeVariant(
                    UUID.randomUUID(), resumeId, ResumeVariant.LANGUAGE_EN,
                    englishPdf.document().id(), englishPdf.generatedFile().path(), now, now
            );
            ResumeVariant deVariant = new ResumeVariant(
                    UUID.randomUUID(), resumeId, ResumeVariant.LANGUAGE_DE,
                    germanPdf.document().id(), germanPdf.generatedFile().path(), now, now
            );

            ReplaceResult replaced = resumeRepository.replaceGermanyResume(new ResumeAggregateWrite(
                    resume,
                    List.of(
                            toVariantWrite(enVariant, english),
                            toVariantWrite(deVariant, german)
                    )
            ));

            for (ResumeVariant previous : replaced.previousVariants()) {
                documentRepository.deleteFileIfUnreferenced(previous.documentId());
                cleanupService.enqueueAfterCommit(previous.filePath());
            }

            return new GenerateGermanTailoredResumeResult(
                    resume.id(),
                    profileId,
                    jobId,
                    FORMAT_GERMANY,
                    List.of(
                            variantResult(enVariant, englishPdf),
                            variantResult(deVariant, germanPdf)
                    ),
                    !replaced.previousVariants().isEmpty(),
                    now.toString()
            );
        } catch (RuntimeException | Error failure) {
            discard(englishPdf);
            discard(germanPdf);
            throw failure;
        }
    }

    private GeneratedPdfAssets generatePdf(
            StructuredResumeContent content,
            UUID profileId,
            UUID jobId,
            String language
    ) {
        String fileName = "german-resume-" + language + "-" + profileId + "-" + jobId + "-" + UUID.randomUUID() + ".pdf";
        String title = content.fullName() + (ResumeVariant.LANGUAGE_DE.equals(language) ? " - Lebenslauf" : " - CV");
        GeneratedPdfFileResult generatedFile = new PdfGenerationService(outputDirectory).generatePdfFile(
                new PdfGenerationService.GeneratePdfFileRequest(fileName, title, GermanLebenslaufBodyRenderer.render(content))
        );
        transactionLifecycle.afterRollback(() -> fileRepository.deleteIfExists(generatedFile.path()));
        try {
            StoredDocumentMetadata document = documentStorageService.storeGeneratedDocumentFile(
                    Path.of(generatedFile.path()),
                    DocumentStorageService.PDF_MEDIA_TYPE
            );
            return new GeneratedPdfAssets(document, generatedFile);
        } catch (RuntimeException | Error failure) {
            try {
                fileRepository.deleteIfExists(generatedFile.path());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static VariantWrite toVariantWrite(ResumeVariant variant, StructuredResumeContent content) {
        List<SectionWrite> sections = new ArrayList<>();
        int sectionOrder = 0;

        if (!content.personalFields().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.PERSONAL,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Persönliche Daten" : "Personal Data",
                    sectionOrder++
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.PersonalField field : content.personalFields()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.PERSONAL_FIELD, entryOrder++,
                        field.label(), null, null, null, null, field.value()
                );
                entries.add(new EntryWrite(entry, List.of()));
            }
            sections.add(new SectionWrite(section, entries));
        }

        if (!content.experiences().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.EXPERIENCE,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Berufserfahrung" : "Professional Experience",
                    sectionOrder++
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.ExperienceEntry experience : content.experiences()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.EXPERIENCE, entryOrder++,
                        experience.title(), experience.company(), experience.location(),
                        experience.startDate(), experience.endDate(), null
                );
                entries.add(new EntryWrite(entry, bullets(entry.id(), experience.bullets())));
            }
            sections.add(new SectionWrite(section, entries));
        }

        if (!content.education().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.EDUCATION,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Ausbildung" : "Education",
                    sectionOrder++
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.EducationEntry education : content.education()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.EDUCATION, entryOrder++,
                        education.degree(), education.institution(), education.location(),
                        education.startDate(), education.endDate(), null
                );
                entries.add(new EntryWrite(entry, bullets(entry.id(), education.bullets())));
            }
            sections.add(new SectionWrite(section, entries));
        }

        if (!content.skillGroups().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.SKILLS,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Kenntnisse" : "Skills",
                    sectionOrder++
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.SkillGroup group : content.skillGroups()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.SKILL_GROUP, entryOrder++,
                        group.category(), null, null, null, null, String.join(", ", group.skills())
                );
                entries.add(new EntryWrite(entry, List.of()));
            }
            sections.add(new SectionWrite(section, entries));
        }

        if (!content.languages().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.LANGUAGES,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Sprachen" : "Languages",
                    sectionOrder++
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.LanguageEntry language : content.languages()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.LANGUAGE, entryOrder++,
                        language.language(), null, null, null, null, language.proficiency()
                );
                entries.add(new EntryWrite(entry, List.of()));
            }
            sections.add(new SectionWrite(section, entries));
        }

        if (!content.additional().isEmpty()) {
            ResumeSection section = new ResumeSection(
                    UUID.randomUUID(), variant.id(), ResumeSection.ADDITIONAL,
                    ResumeVariant.LANGUAGE_DE.equals(content.language()) ? "Weitere Qualifikationen" : "Additional Qualifications",
                    sectionOrder
            );
            List<EntryWrite> entries = new ArrayList<>();
            int entryOrder = 0;
            for (StructuredResumeContent.AdditionalEntry additional : content.additional()) {
                ResumeEntry entry = new ResumeEntry(
                        UUID.randomUUID(), section.id(), ResumeEntry.PROJECT, entryOrder++,
                        additional.title(), additional.organization(), null, null, null, null
                );
                entries.add(new EntryWrite(entry, bullets(entry.id(), additional.bullets())));
            }
            sections.add(new SectionWrite(section, entries));
        }

        return new VariantWrite(variant, sections);
    }

    private static List<ResumeEntryBullet> bullets(UUID entryId, List<String> texts) {
        List<ResumeEntryBullet> bullets = new ArrayList<>();
        int order = 0;
        for (String text : texts) {
            bullets.add(new ResumeEntryBullet(UUID.randomUUID(), entryId, order++, text));
        }
        return bullets;
    }

    private static VariantResult variantResult(ResumeVariant variant, GeneratedPdfAssets assets) {
        return new VariantResult(
                variant.id(),
                variant.language(),
                variant.documentId(),
                variant.filePath(),
                assets.document(),
                assets.generatedFile()
        );
    }

    private void discard(GeneratedPdfAssets assets) {
        if (assets == null) {
            return;
        }
        try {
            fileRepository.deleteIfExists(assets.generatedFile().path());
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    private static UUID requireId(UUID id, String field) {
        if (id == null) {
            throw new ApplicationException(
                    ApplicationErrorCode.VALIDATION_ERROR,
                    "Invalid German tailored resume request",
                    Map.of("field", field, "reason", "must not be null"),
                    null
            );
        }
        return id;
    }

    public record GenerateGermanTailoredResumeRequest(UUID profileId, UUID jobId) {
    }

    public record GenerateGermanTailoredResumeResult(
            UUID resumeId,
            UUID profileId,
            UUID jobId,
            String format,
            List<VariantResult> variants,
            boolean replacedExisting,
            String generatedAt
    ) {
    }

    public record VariantResult(
            UUID variantId,
            String language,
            UUID documentId,
            String filePath,
            StoredDocumentMetadata document,
            GeneratedPdfFileResult generatedFile
    ) {
    }

    private record GeneratedPdfAssets(StoredDocumentMetadata document, GeneratedPdfFileResult generatedFile) {
    }
}
