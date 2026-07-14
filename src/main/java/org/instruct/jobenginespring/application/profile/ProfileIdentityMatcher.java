package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import org.apache.commons.codec.digest.DigestUtils;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch.LinkIdentity;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Finds existing profiles that appear to represent the same person as an extracted profile draft. */
@Service
@RequiredArgsConstructor
public class ProfileIdentityMatcher {

    @NonNull
    private final ProfileRepository profileRepository;

    public Optional<ProfileIdentityMatch> findStrongMatch(ProfileWriteRequest request) {
        ProfileWriteValidator.validate(request);
        ProfileWriteRequest canonical = ProfileWriteCanonicalizer.canonicalize(request);
        ProfileIdentitySearch search = new ProfileIdentitySearch(
                canonical.email(),
                identityLinks(canonical.links())
        );
        List<ProfileIdentityCandidate> candidates = profileRepository.findIdentityCandidates(search);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, java.util.LinkedHashSet<String>> reasonsByProfile = new LinkedHashMap<>();
        for (ProfileIdentityCandidate candidate : candidates) {
            reasonsByProfile.computeIfAbsent(candidate.profileId(), ignored -> new java.util.LinkedHashSet<>())
                    .add(candidate.matchedOn());
        }
        Map.Entry<UUID, java.util.LinkedHashSet<String>> strongest = reasonsByProfile.entrySet().iterator().next();
        return Optional.of(new ProfileIdentityMatch(
                strongest.getKey(),
                strongest.getValue().stream().toList(),
                reasonsByProfile.size() > 1
        ));
    }

    /** Returns sorted, one-way keys suitable only for serializing competing identity mutations. */
    public List<String> concurrencyKeys(ProfileWriteRequest request) {
        ProfileWriteValidator.validate(request);
        ProfileWriteRequest canonical = ProfileWriteCanonicalizer.canonicalize(request);
        return Stream.concat(
                        Stream.of("email:" + canonical.email()),
                        identityLinks(canonical.links()).stream()
                                .map(link -> "link:" + link.linkType() + ":" + link.normalizedUrl())
                )
                .map(DigestUtils::sha256Hex)
                .distinct()
                .sorted()
                .toList();
    }

    private static List<LinkIdentity> identityLinks(List<LinkWriteRequest> links) {
        return links.stream()
                .map(link -> new LinkIdentity(link.linkType(), ProfileWriteCanonicalizer.canonicalUrl(link.url())))
                .distinct()
                .toList();
    }

    static String normalizeUrl(String rawUrl) {
        return ProfileWriteCanonicalizer.canonicalUrl(rawUrl);
    }

    public record ProfileIdentityMatch(UUID profileId, List<String> matchedOn, boolean ambiguous) {

        public ProfileIdentityMatch {
            matchedOn = matchedOn == null ? List.of() : List.copyOf(matchedOn);
        }
    }
}
