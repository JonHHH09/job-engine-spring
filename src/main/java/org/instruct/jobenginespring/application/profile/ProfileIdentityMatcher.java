package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.profile.ProfileIdentitySearch.LinkIdentity;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Finds existing profiles that appear to represent the same person as an extracted profile draft. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
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

    private static List<LinkIdentity> identityLinks(List<LinkWriteRequest> links) {
        return links.stream()
                .map(link -> new LinkIdentity(link.linkType(), normalizeUrl(link.url())))
                .distinct()
                .toList();
    }

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String prepared = rawUrl.strip().replaceAll("[.,;]+$", "");
        if (!prepared.toLowerCase(Locale.ROOT).startsWith("http://")
                && !prepared.toLowerCase(Locale.ROOT).startsWith("https://")) {
            prepared = "https://" + prepared;
        }
        try {
            URI uri = new URI(prepared);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return new URI(scheme, null, host, uri.getPort(), path, null, null).toString().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException exception) {
            return prepared.replaceAll("[?#].*$", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        }
    }

    public record ProfileIdentityMatch(UUID profileId, List<String> matchedOn, boolean ambiguous) {

        public ProfileIdentityMatch {
            matchedOn = matchedOn == null ? List.of() : List.copyOf(matchedOn);
        }
    }
}
