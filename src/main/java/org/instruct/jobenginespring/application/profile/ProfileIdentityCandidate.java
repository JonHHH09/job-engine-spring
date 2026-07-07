package org.instruct.jobenginespring.application.profile;

import java.util.UUID;

/** A profile identity match candidate found through canonical profile fields. */
public record ProfileIdentityCandidate(UUID profileId, String matchedOn) {
}
