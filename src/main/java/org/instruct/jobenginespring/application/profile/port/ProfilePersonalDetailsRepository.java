package org.instruct.jobenginespring.application.profile.port;

import org.instruct.jobenginespring.domain.profile.ProfilePersonalDetails;

import java.util.Optional;
import java.util.UUID;

public interface ProfilePersonalDetailsRepository {
    Optional<ProfilePersonalDetails> findByProfileId(UUID profileId);

    ProfilePersonalDetails save(ProfilePersonalDetails details);
}
