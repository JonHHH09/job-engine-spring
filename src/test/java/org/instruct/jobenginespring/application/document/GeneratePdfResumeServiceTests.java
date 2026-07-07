package org.instruct.jobenginespring.application.document;

import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeRequest;
import org.instruct.jobenginespring.application.document.GeneratePdfResumeService.GeneratePdfResumeResult;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.application.security.McpAccessPolicy;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GeneratePdfResumeServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-05T15:30:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @TempDir
    Path tempDir;

    private FakeProfileRepository profileRepository;
    private InMemoryDocumentRepository documentRepository;
    private InMemoryProfileResumeDocumentRepository resumeDocumentRepository;
    private GeneratePdfResumeService service;
    private GenerateCaPdfResumeService caService;

    @BeforeEach
    void setUp() {
        profileRepository = new FakeProfileRepository();
        documentRepository = new InMemoryDocumentRepository();
        resumeDocumentRepository = new InMemoryProfileResumeDocumentRepository();
        DocumentStorageService documentStorageService = new DocumentStorageService(
                documentRepository,
                mock(PdfTextExtractionService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ProfileResumePdfGenerationWorkflow workflow = new ProfileResumePdfGenerationWorkflow(
                profileRepository,
                documentStorageService,
                resumeDocumentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        service = new GeneratePdfResumeService(workflow, tempDir.resolve("master-resume"));
        caService = new GenerateCaPdfResumeService(workflow, tempDir.resolve("canadian-resume"));
    }

    @Test
    void generatesMasterResumePdfStoresDocumentAndLinksItToProfile() throws Exception {
        profileRepository.saveProfileAggregate(sampleAggregate(PROFILE_ID));

        GeneratePdfResumeResult result = service.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID));

        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(GeneratePdfResumeService.MASTER_RESUME_TYPE, result.resumeType());
        assertFalse(result.replacedExistingLink());
        assertEquals(NOW.toString(), result.linkedAt());
        assertTrue(result.filePath().endsWith("master-resume-" + PROFILE_ID + ".pdf"));
        assertEquals(result.filePath(), result.generatedFile().path());
        assertEquals(result.documentId(), result.document().id());
        assertEquals(result.documentId(), resumeDocumentRepository.findByProfileIdAndResumeType(
                PROFILE_ID,
                GeneratePdfResumeService.MASTER_RESUME_TYPE
        ).orElseThrow().documentId());

        byte[] generatedBytes = Files.readAllBytes(Path.of(result.filePath()));
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F', '-'}, java.util.Arrays.copyOf(generatedBytes, 5));
        assertArrayEquals(generatedBytes, documentRepository.findFileContentById(result.documentId()).orElseThrow().content());

        PdfTextExtractionService.PdfTextExtractionResult text = new PdfTextExtractionService()
                .extractText(generatedBytes, "resume.pdf", null, false);
        assertTrue(text.text().contains("Agentic Dev"));
        assertTrue(text.text().contains("agentic@example.test"));
        assertTrue(text.text().contains("Java"));
        assertTrue(text.text().contains("Job Engine Spring"));
        assertEquals(1, countOccurrences(text.text(), "Agentic Dev - Master Resume"));
        assertEquals(2, countOccurrences(text.text(), "Page 1 of 1"));
    }

    @Test
    void repeatedGenerationKeepsOneProfileLinkAndPointsItToLatestStoredDocument() {
        profileRepository.saveProfileAggregate(sampleAggregate(PROFILE_ID));

        GeneratePdfResumeResult first = service.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID));
        GeneratePdfResumeResult second = service.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID));

        assertTrue(second.replacedExistingLink());
        assertEquals(first.resumeLinkId(), second.resumeLinkId());
        assertNotEquals(first.documentId(), second.documentId());
        assertEquals(1, resumeDocumentRepository.count());
        assertEquals(second.documentId(), resumeDocumentRepository.findByProfileIdAndResumeType(
                PROFILE_ID,
                GeneratePdfResumeService.MASTER_RESUME_TYPE
        ).orElseThrow().documentId());
        assertEquals(2, documentRepository.fileCount());
    }

    @Test
    void masterAndCanadianResumeVariantsCanCoexistForOneProfile() throws Exception {
        profileRepository.saveProfileAggregate(sampleAggregate(PROFILE_ID));

        GeneratePdfResumeResult master = service.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID));
        GeneratePdfResumeResult canadian = caService.generateCanadianPdfResume(new GenerateCaPdfResumeService.GenerateCaPdfResumeRequest(PROFILE_ID));

        assertEquals(GeneratePdfResumeService.MASTER_RESUME_TYPE, master.resumeType());
        assertEquals(GenerateCaPdfResumeService.CANADIAN_RESUME_TYPE, canadian.resumeType());
        assertNotEquals(master.resumeLinkId(), canadian.resumeLinkId());
        assertEquals(2, resumeDocumentRepository.count());
        assertEquals(master.documentId(), resumeDocumentRepository.findByProfileIdAndResumeType(
                PROFILE_ID,
                GeneratePdfResumeService.MASTER_RESUME_TYPE
        ).orElseThrow().documentId());
        assertEquals(canadian.documentId(), resumeDocumentRepository.findByProfileIdAndResumeType(
                PROFILE_ID,
                GenerateCaPdfResumeService.CANADIAN_RESUME_TYPE
        ).orElseThrow().documentId());
        assertTrue(canadian.filePath().endsWith("canadian-resume-" + PROFILE_ID + ".pdf"));

        PdfTextExtractionService.PdfTextExtractionResult text = new PdfTextExtractionService()
                .extractText(Files.readAllBytes(Path.of(canadian.filePath())), "canadian-resume.pdf", null, false);
        assertEquals(1, countOccurrences(text.text(), "Agentic Dev"));
        assertFalse(text.text().contains("Canadian Resume"));
        assertEquals(2, countOccurrences(text.text(), "Page 1 of 1"));
        assertTrue(text.text().contains("Contact: agentic@example.test | Location: Montreal, QC"));
        assertFalse(text.text().contains("Personal/Resume email"));
        assertFalse(text.text().contains("personal@example.test"));
        assertTrue(text.text().contains("Links: GitHub: https://github.example/agentic"));
        assertTrue(text.text().indexOf("Links: GitHub: https://github.example/agentic") < text.text().indexOf("PROFESSIONAL SUMMARY"));
        assertTrue(text.text().contains("PROFESSIONAL SUMMARY"));
        assertTrue(text.text().contains("TECHNICAL SKILLS"));
        assertTrue(text.text().contains("PROFESSIONAL EXPERIENCE"));
        assertTrue(text.text().contains("EDUCATION"));
        assertTrue(text.text().contains("B.A., Computer Science"));
        assertFalse(text.text().contains("PROJECTS"));
        assertFalse(text.text().contains("Job Engine Spring"));
    }

    @Test
    void reportsMissingProfileAsNotFound() {
        org.instruct.jobenginespring.application.error.ApplicationException exception = assertThrows(
                org.instruct.jobenginespring.application.error.ApplicationException.class,
                () -> service.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID))
        );

        assertEquals("not_found", exception.errorCode().code());
    }

    @Test
    void rejectsResumeGenerationWithoutValidAccessTokenBeforeLoadingProfile() {
        ProfileResumePdfGenerationWorkflow workflow = mock(ProfileResumePdfGenerationWorkflow.class);
        GeneratePdfResumeService securedMasterService = new GeneratePdfResumeService(
                workflow,
                McpAccessPolicy.configured("secret"),
                tempDir.resolve("secured-master").toString()
        );
        GenerateCaPdfResumeService securedCanadianService = new GenerateCaPdfResumeService(
                workflow,
                McpAccessPolicy.configured("secret"),
                tempDir.resolve("secured-canadian").toString()
        );

        ApplicationException masterDenied = assertThrows(
                ApplicationException.class,
                () -> securedMasterService.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID, "wrong"))
        );
        ApplicationException canadianDenied = assertThrows(
                ApplicationException.class,
                () -> securedCanadianService.generateCanadianPdfResume(
                        new GenerateCaPdfResumeService.GenerateCaPdfResumeRequest(PROFILE_ID, null)
                )
        );

        assertEquals("authorization_error", masterDenied.errorCode().code());
        assertEquals("authorization_error", canadianDenied.errorCode().code());
        verifyNoInteractions(workflow);
    }

    @Test
    void rejectsNullProfileIdWithValidationError() {
        org.instruct.jobenginespring.application.error.ApplicationException exception = assertThrows(
                org.instruct.jobenginespring.application.error.ApplicationException.class,
                () -> service.generatePdfResume(new GeneratePdfResumeRequest(null))
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("profileId", exception.details().get("field"));
    }

    @Test
    void coversDefaultIdentityCandidateLookupAndNullIngestionMatchList() {
        assertEquals(List.of(), profileRepository.findIdentityCandidates(new ProfileIdentitySearch("agentic@example.test", null)));

        ProfilePdfIngestionService.ProfilePdfIngestionResult result = new ProfilePdfIngestionService.ProfilePdfIngestionResult(
                ProfilePdfIngestionService.IngestionStatus.VALIDATION_FAILED,
                PROFILE_ID,
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                null,
                "resume.pdf",
                1,
                10,
                false,
                false,
                false,
                null,
                null,
                null
        );

        assertEquals(List.of(), result.matchedOn());
    }

    @Test
    void rejectsInvalidResumeOutputConfiguration() {
        ProfileResumePdfGenerationWorkflow workflow = new ProfileResumePdfGenerationWorkflow(
                profileRepository,
                new DocumentStorageService(documentRepository, mock(PdfTextExtractionService.class), Clock.fixed(NOW, ZoneOffset.UTC)),
                resumeDocumentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        GeneratePdfResumeService defaultService = new GeneratePdfResumeService(workflow, (String) null);
        assertThrows(org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException.class,
                () -> defaultService.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID)));
        GeneratePdfResumeService blankDefaultService = new GeneratePdfResumeService(workflow, " ");
        assertThrows(org.instruct.jobenginespring.application.profile.ProfileService.ProfileNotFoundException.class,
                () -> blankDefaultService.generatePdfResume(new GeneratePdfResumeRequest(PROFILE_ID)));

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> new GeneratePdfResumeService(workflow, "bad\0path")
        );

        assertEquals("validation_error", exception.errorCode().code());
        assertEquals("outputDirectory", exception.details().get("field"));
    }

    @Test
    void rejectsBlankResumeTypeInSharedWorkflow() {
        profileRepository.saveProfileAggregate(sampleAggregate(PROFILE_ID));
        ProfileResumePdfGenerationWorkflow workflow = new ProfileResumePdfGenerationWorkflow(
                profileRepository,
                new DocumentStorageService(documentRepository, mock(PdfTextExtractionService.class), Clock.fixed(NOW, ZoneOffset.UTC)),
                resumeDocumentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ApplicationException blankException = assertThrows(
                ApplicationException.class,
                () -> workflow.generateAndLink(new ProfileResumePdfGenerationWorkflow.GenerateProfileResumePdfCommand(
                        PROFILE_ID,
                        " ",
                        tempDir,
                        "resume.pdf",
                        "Resume",
                        "Body"
                ))
        );
        ApplicationException nullException = assertThrows(
                ApplicationException.class,
                () -> workflow.generateAndLink(new ProfileResumePdfGenerationWorkflow.GenerateProfileResumePdfCommand(
                        PROFILE_ID,
                        null,
                        tempDir,
                        "resume.pdf",
                        "Resume",
                        "Body"
                ))
        );

        assertEquals("validation_error", blankException.errorCode().code());
        assertEquals("resumeType", blankException.details().get("field"));
        assertEquals("validation_error", nullException.errorCode().code());
        assertEquals("resumeType", nullException.details().get("field"));
    }

    @Test
    void rendersCanadianResumeWithOptionalFallbacksAndReverseChronologicalExperience() {
        ProfileAggregate aggregate = sparseAggregate(PROFILE_ID);

        String rendered = ResumeBodyRenderer.renderCanadianResume(aggregate);
        String emptyRendered = ResumeBodyRenderer.renderMasterResume(emptyAggregate(PROFILE_ID));

        assertTrue(rendered.contains("Profile summary not provided."));
        assertFalse(rendered.contains("Agentic Dev"));
        assertTrue(rendered.contains("personal: +1-555-0100"));
        assertTrue(rendered.indexOf("portfolio: https://example.test") < rendered.indexOf("PROFESSIONAL SUMMARY"));
        assertTrue(rendered.contains("English"));
        assertTrue(rendered.contains("Education"));
        assertTrue(rendered.contains("Dates not provided"));
        assertFalse(rendered.contains("PROJECTS"));
        assertFalse(rendered.contains("Project"));
        assertTrue(rendered.indexOf("Current Role") < rendered.indexOf("Past Role"));
        assertTrue(rendered.indexOf("Past Role") < rendered.indexOf("Undated Role"));
        assertTrue(emptyRendered.contains("Agentic Dev"));
        assertFalse(emptyRendered.contains("SKILLS"));
        assertFalse(emptyRendered.contains("EXPERIENCE"));
    }

    @Test
    void canadianResumeDerivesCappedExperienceBulletsFromExistingDescriptionText() {
        ProfileAggregate aggregate = experienceBulletAggregate(PROFILE_ID);

        String rendered = ResumeBodyRenderer.renderCanadianResume(aggregate);
        String masterRendered = ResumeBodyRenderer.renderMasterResume(aggregate);

        assertTrue(rendered.contains("- Built MCP-native PDF generation tools."));
        assertTrue(rendered.contains("- Added deterministic resume rendering tests."));
        assertTrue(rendered.contains("- Improved document provenance handling."));
        assertFalse(rendered.contains("Kept this fourth sentence out of the Canadian resume."));
        assertTrue(rendered.contains("- Preserved existing bullet text"));
        assertTrue(rendered.contains("- Normalized star-prefixed bullet text"));
        assertTrue(rendered.contains("- Normalized numbered bullet text"));
        assertFalse(rendered.contains("Hidden fourth existing bullet"));
        assertTrue(masterRendered.contains("- Built MCP-native PDF generation tools. Added deterministic resume rendering tests. Improved document provenance handling. Kept this fourth sentence out of the Canadian resume."));
    }

    @Test
    void canadianResumeFiltersEmailContactsByTypeLabelOrValueAndKeepsNonEmailContacts() {
        ProfileAggregate aggregate = contactFilteringAggregate(PROFILE_ID);

        String rendered = ResumeBodyRenderer.renderCanadianResume(aggregate);

        assertTrue(rendered.contains("Contact: agentic@example.test | Phone: +1-555-0100 | Website: https://example.test"));
        assertFalse(rendered.contains("type-filtered@example.test"));
        assertFalse(rendered.contains("label-filtered@example.test"));
        assertFalse(rendered.contains("value-filtered@example.test"));
        assertFalse(rendered.contains("Links:"));
    }

    @Test
    void masterResumeRendersProjectsWithOptionalFallbacksAndBlankOptionalFields() {
        ProfileAggregate aggregate = projectFallbackAggregate(PROFILE_ID);

        String rendered = ResumeBodyRenderer.renderMasterResume(aggregate);

        assertTrue(rendered.contains("Project - https://example.test/fallback"));
        assertTrue(rendered.contains("Named Project"));
        assertFalse(rendered.contains("Named Project -"));
        assertFalse(rendered.contains("Technologies:"));
        assertFalse(rendered.contains("-  "));
    }

    @Test
    void coversPrivateResumeBulletAndSentenceFallbackEdges() throws Exception {
        assertEquals(List.of(), invokeResumeList("experienceBullets", new Class<?>[]{String.class, int.class}, " ", 3));
        assertEquals(List.of(), invokeResumeList("experienceBullets", new Class<?>[]{String.class, int.class}, "Handled platform work.", 0));
        assertEquals(List.of(), invokeResumeList("splitSentences", new Class<?>[]{String.class}, " "));
        assertEquals(List.of("."), invokeResumeList("splitSentences", new Class<?>[]{String.class}, " . "));
        assertEquals(List.of("Normalized bullet"), invokeResumeList("experienceBullets", new Class<?>[]{String.class, int.class}, "• Normalized bullet", 3));
        assertEquals("-", invokeResumeString("stripBulletPrefix", new Class<?>[]{String.class}, "-"));
    }

    private static ProfileAggregate emptyAggregate(UUID profileId) {
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", null, null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ProfileAggregate sparseAggregate(UUID profileId) {
        UUID projectId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", " ", null, NOW, NOW),
                List.of(new ProfileContact(UUID.randomUUID(), profileId, "personal", "+1-555-0100", " ", NOW, NOW)),
                List.of(new ProfileLink(UUID.randomUUID(), profileId, "portfolio", "https://example.test", " ", NOW, NOW)),
                List.of(),
                List.of(new ProfileLanguage(UUID.randomUUID(), profileId, "English", "english", " ", 0, NOW)),
                List.of(new Education(UUID.randomUUID(), profileId, " ", " ", " ", " ", null, null, " ", NOW)),
                List.of(
                        new Experience(UUID.randomUUID(), profileId, "Current Co", "Current Role", " ", LocalDate.of(2025, 1, 1), null, " ", 3, NOW),
                        new Experience(UUID.randomUUID(), profileId, "End Only Co", "End Only Role", null, null, LocalDate.of(2024, 1, 1), null, 2, NOW),
                        new Experience(UUID.randomUUID(), profileId, "Past Co", "Past Role", null, LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1), "Past work", 1, NOW),
                        new Experience(UUID.randomUUID(), profileId, "Undated Co", "Undated Role", null, null, null, null, 0, NOW)
                ),
                List.of(new ProfileProject(projectId, profileId, "Project", " ", " ", List.of(), 0, NOW)),
                List.of()
        );
    }

    private static ProfileAggregate experienceBulletAggregate(UUID profileId) {
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", "Builds MCP-native systems.", null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new Experience(
                                UUID.randomUUID(),
                                profileId,
                                "Current Co",
                                "Current Role",
                                "Remote",
                                LocalDate.of(2025, 1, 1),
                                null,
                                "Built MCP-native PDF generation tools. Added deterministic resume rendering tests. Improved document provenance handling. Kept this fourth sentence out of the Canadian resume.",
                                0,
                                NOW
                        ),
                        new Experience(
                                UUID.randomUUID(),
                                profileId,
                                "Past Co",
                                "Past Role",
                                "Remote",
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                "- Preserved existing bullet text\n* Normalized star-prefixed bullet text\n1. Normalized numbered bullet text\n- Hidden fourth existing bullet",
                                1,
                                NOW
                        )
                ),
                List.of(),
                List.of()
        );
    }

    private static ProfileAggregate contactFilteringAggregate(UUID profileId) {
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", "Builds MCP-native systems.", null, NOW, NOW),
                List.of(
                        new ProfileContact(UUID.randomUUID(), profileId, "Email", "type-filtered@example.test", "Personal", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), profileId, "personal", "label-filtered@example.test", "Email", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), profileId, "personal", "value-filtered@example.test", "Website", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), profileId, "phone", "+1-555-0100", "Phone", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), profileId, "website", "https://example.test", "Website", NOW, NOW)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ProfileAggregate projectFallbackAggregate(UUID profileId) {
        UUID fallbackProjectId = UUID.randomUUID();
        UUID namedProjectId = UUID.randomUUID();
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", "Builds MCP-native systems.", null, NOW, NOW),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new ProfileProject(fallbackProjectId, profileId, " ", "https://example.test/fallback", " ", List.of(), 0, NOW),
                        new ProfileProject(namedProjectId, profileId, "Named Project", " ", " ", List.of(), 1, NOW)
                ),
                List.of()
        );
    }

    private static ProfileAggregate sampleAggregate(UUID profileId) {
        UUID projectId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        return new ProfileAggregate(
                new UserProfile(profileId, "Agentic Dev", "agentic@example.test", "Builds MCP-native systems.", null, NOW, NOW),
                List.of(
                        new ProfileContact(UUID.randomUUID(), profileId, "location", "Montreal, QC", "Location", NOW, NOW),
                        new ProfileContact(UUID.randomUUID(), profileId, "email", "personal@example.test", "Personal/Resume email", NOW, NOW)
                ),
                List.of(new ProfileLink(UUID.randomUUID(), profileId, "github", "https://github.example/agentic", "GitHub", NOW, NOW)),
                List.of(new ProfileSkill(UUID.randomUUID(), profileId, "Java", "java", "Backend", 0, NOW)),
                List.of(new ProfileLanguage(UUID.randomUUID(), profileId, "English", "english", "Fluent", 0, NOW)),
                List.of(new Education(UUID.randomUUID(), profileId, "AUBG", "B.A.", "Computer Science", "Blagoevgrad", LocalDate.of(2019, 9, 1), LocalDate.of(2023, 5, 1), "Software engineering", NOW)),
                List.of(new Experience(UUID.randomUUID(), profileId, "Instruct", "Software Developer", "Remote", LocalDate.of(2024, 1, 1), null, "Built verification-first tools.", 0, NOW)),
                List.of(new ProfileProject(projectId, profileId, "Job Engine Spring", "https://example.test/job-engine-spring", "Spring MCP replacement.", List.of(
                        new ProjectTechnology(UUID.randomUUID(), projectId, "Spring Boot", "spring boot", 0, NOW)
                ), 0, NOW)),
                List.of(new ProjectTechnology(UUID.randomUUID(), projectId, "Spring Boot", "spring boot", 0, NOW))
        );
    }

    private static final class FakeProfileRepository implements ProfileRepository {
        private final Map<UUID, ProfileAggregate> aggregates = new LinkedHashMap<>();

        @Override
        public List<UserProfile> listProfiles() {
            return aggregates.values().stream().map(ProfileAggregate::profile).toList();
        }

        @Override
        public Optional<UserProfile> findProfileById(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId)).map(ProfileAggregate::profile);
        }

        @Override
        public List<ProfileContact> listContacts(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::contacts).orElse(List.of());
        }

        @Override
        public List<ProfileLink> listLinks(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::links).orElse(List.of());
        }

        @Override
        public List<ProfileSkill> listSkills(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::skills).orElse(List.of());
        }

        @Override
        public List<ProfileLanguage> listLanguages(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::languages).orElse(List.of());
        }

        @Override
        public List<Education> listEducation(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::education).orElse(List.of());
        }

        @Override
        public List<Experience> listExperiences(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::experiences).orElse(List.of());
        }

        @Override
        public List<ProfileProject> listProjects(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::projects).orElse(List.of());
        }

        @Override
        public List<ProjectTechnology> listProjectTechnologies(UUID profileId) {
            return aggregate(profileId).map(ProfileAggregate::projectTechnologies).orElse(List.of());
        }

        @Override
        public ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate) {
            aggregates.put(aggregate.profile().id(), aggregate);
            return aggregate;
        }

        @Override
        public boolean deleteProfile(UUID profileId) {
            return aggregates.remove(profileId) != null;
        }

        private Optional<ProfileAggregate> aggregate(UUID profileId) {
            return Optional.ofNullable(aggregates.get(profileId));
        }
    }

    private static final class InMemoryDocumentRepository implements DocumentRepository {
        private final Map<UUID, StoredDocumentFile> files = new LinkedHashMap<>();

        @Override
        public StoredDocumentMetadata saveFile(StoredDocumentFile file) {
            files.put(file.id(), file);
            return file.metadata();
        }

        @Override
        public Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId) {
            return Optional.ofNullable(files.get(fileId)).map(StoredDocumentFile::metadata);
        }

        @Override
        public Optional<StoredDocumentMetadata> findFileMetadataBySha256(String sha256) {
            return files.values().stream()
                    .filter(file -> file.sha256().equals(sha256))
                    .findFirst()
                    .map(StoredDocumentFile::metadata);
        }

        @Override
        public Optional<StoredDocumentFile> findFileContentById(UUID fileId) {
            return Optional.ofNullable(files.get(fileId));
        }

        @Override
        public Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId) {
            return Optional.empty();
        }

        @Override
        public PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction) {
            return extraction;
        }

        private int fileCount() {
            return files.size();
        }
    }

    private static final class InMemoryProfileResumeDocumentRepository implements ProfileResumeDocumentRepository {
        private final Map<String, ProfileResumeDocument> linksByProfileAndType = new LinkedHashMap<>();

        @Override
        public ProfileResumeDocument save(ProfileResumeDocument resumeDocument) {
            String key = key(resumeDocument.profileId(), resumeDocument.resumeType());
            ProfileResumeDocument existing = linksByProfileAndType.get(key);
            ProfileResumeDocument saved = existing == null
                    ? resumeDocument
                    : new ProfileResumeDocument(
                    existing.id(),
                    resumeDocument.profileId(),
                    resumeDocument.documentId(),
                    resumeDocument.filePath(),
                    resumeDocument.resumeType(),
                    existing.createdAt(),
                    resumeDocument.updatedAt()
            );
            linksByProfileAndType.put(key, saved);
            return saved;
        }

        @Override
        public Optional<ProfileResumeDocument> findByProfileIdAndResumeType(UUID profileId, String resumeType) {
            return Optional.ofNullable(linksByProfileAndType.get(key(profileId, resumeType)));
        }

        @Override
        public Optional<ProfileResumeDocument> findByDocumentId(UUID documentId) {
            return linksByProfileAndType.values().stream()
                    .filter(link -> link.documentId().equals(documentId))
                    .findFirst();
        }

        private int count() {
            return linksByProfileAndType.size();
        }

        private static String key(UUID profileId, String resumeType) {
            return profileId + ":" + resumeType;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeResumeList(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        return (List<String>) invokeResume(methodName, parameterTypes, args);
    }

    private static String invokeResumeString(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        return (String) invokeResume(methodName, parameterTypes, args);
    }

    private static Object invokeResume(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ResumeBodyRenderer.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
