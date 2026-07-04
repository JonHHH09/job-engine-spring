package org.instruct.jobenginespring.application.profile;

import java.util.List;

/** Canonical identity fields extracted from a profile write request for duplicate detection. */
public record ProfileIdentitySearch(String email, List<LinkIdentity> links) {

    public ProfileIdentitySearch {
        links = links == null ? List.of() : List.copyOf(links);
    }

    public record LinkIdentity(String linkType, String normalizedUrl) {
    }
}
