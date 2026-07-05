package org.instruct.jobenginespring;

import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.port.DocumentRepository;
import org.instruct.jobenginespring.application.profile.port.ProfilePdfSourceRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.application.profile.port.ProfileResumeDocumentRepository;
import org.instruct.jobenginespring.domain.document.PdfExtractionRecord;
import org.instruct.jobenginespring.domain.document.StoredDocumentFile;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileResumeDocument;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                + "org.springframework.ai.model.postgresml.autoconfigure.PostgresMlEmbeddingAutoConfiguration",
        "job-engine.health.postgres.enabled=false",
        "job-engine.profile.postgres.enabled=false",
        "job-engine.document.postgres.enabled=false"
})
class JobEngineSpringApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProfileRepositoryTestConfig {

        @Bean
        DatabaseHealthPort databaseHealthPort() {
            return DatabaseHealthCheckResult::up;
        }

        @Bean
        DocumentStorageService documentStorageService() {
            return org.mockito.Mockito.mock(DocumentStorageService.class);
        }

        @Bean
        ProfileRepository profileRepository() {
            return new ProfileRepository() {
                @Override
                public List<UserProfile> listProfiles() {
                    return List.of();
                }

                @Override
                public Optional<UserProfile> findProfileById(UUID profileId) {
                    return Optional.empty();
                }

                @Override
                public List<ProfileContact> listContacts(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<ProfileLink> listLinks(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<ProfileSkill> listSkills(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<ProfileLanguage> listLanguages(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<Education> listEducation(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<Experience> listExperiences(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<ProfileProject> listProjects(UUID profileId) {
                    return List.of();
                }

                @Override
                public List<ProjectTechnology> listProjectTechnologies(UUID profileId) {
                    return List.of();
                }

                @Override
                public ProfileAggregate saveProfileAggregate(ProfileAggregate aggregate) {
                    return aggregate;
                }

                @Override
                public boolean deleteProfile(UUID profileId) {
                    return false;
                }
            };
        }

        @Bean
        DocumentRepository documentRepository() {
            return new DocumentRepository() {
                @Override
                public StoredDocumentMetadata saveFile(StoredDocumentFile file) {
                    return file.metadata();
                }

                @Override
                public Optional<StoredDocumentMetadata> findFileMetadataById(UUID fileId) {
                    return Optional.empty();
                }

                @Override
                public Optional<StoredDocumentMetadata> findFileMetadataBySha256(String sha256) {
                    return Optional.empty();
                }

                @Override
                public Optional<StoredDocumentFile> findFileContentById(UUID fileId) {
                    return Optional.empty();
                }

                @Override
                public Optional<PdfExtractionRecord> findPdfExtractionByFileId(UUID fileId) {
                    return Optional.empty();
                }

                @Override
                public PdfExtractionRecord savePdfExtraction(PdfExtractionRecord extraction) {
                    return extraction;
                }
            };
        }

        @Bean
        ProfilePdfSourceRepository profilePdfSourceRepository() {
            return new ProfilePdfSourceRepository() {
                @Override
                public ProfilePdfSource save(ProfilePdfSource source) {
                    return source;
                }

                @Override
                public Optional<ProfilePdfSource> findByProfileId(UUID profileId) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProfilePdfSource> findByPdfExtractionId(UUID pdfExtractionId) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProfilePdfSource> findByDocumentSha256(String sha256) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        ProfileResumeDocumentRepository profileResumeDocumentRepository() {
            return new ProfileResumeDocumentRepository() {
                @Override
                public ProfileResumeDocument save(ProfileResumeDocument resumeDocument) {
                    return resumeDocument;
                }

                @Override
                public Optional<ProfileResumeDocument> findByProfileIdAndResumeType(UUID profileId, String resumeType) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProfileResumeDocument> findByDocumentId(UUID documentId) {
                    return Optional.empty();
                }
            };
        }
    }

}
