package org.instruct.jobenginespring.config;

import org.instruct.jobenginespring.application.profile.ProfileService;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProfileApplicationConfig {

    @Bean
    ProfileService profileService(ProfileRepository profileRepository) {
        return new ProfileService(profileRepository);
    }
}
