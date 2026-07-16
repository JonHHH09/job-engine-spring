package org.instruct.jobenginespring.application.profile;

import org.instruct.jobenginespring.domain.profile.ProfileProject;

import java.util.Objects;

/** Persisted result of an optimistic single-project profile update. */
public record ProjectUpdateResult(ProfileProject project, long profileRevision) {
    public ProjectUpdateResult {
        project = Objects.requireNonNull(project, "project must not be null");
    }
}
