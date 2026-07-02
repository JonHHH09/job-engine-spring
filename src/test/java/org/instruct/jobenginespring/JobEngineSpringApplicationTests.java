package org.instruct.jobenginespring;

import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.instruct.jobenginespring.domain.profile.Education;
import org.instruct.jobenginespring.domain.profile.Experience;
import org.instruct.jobenginespring.domain.profile.ProfileAggregate;
import org.instruct.jobenginespring.domain.profile.ProfileContact;
import org.instruct.jobenginespring.domain.profile.ProfileLanguage;
import org.instruct.jobenginespring.domain.profile.ProfileLink;
import org.instruct.jobenginespring.domain.profile.ProfileProject;
import org.instruct.jobenginespring.domain.profile.ProfileSkill;
import org.instruct.jobenginespring.domain.profile.ProjectTechnology;
import org.instruct.jobenginespring.domain.profile.UserProfile;
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
        "job-engine.profile.postgres.enabled=false"
})
class JobEngineSpringApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProfileRepositoryTestConfig {

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
    }

}
