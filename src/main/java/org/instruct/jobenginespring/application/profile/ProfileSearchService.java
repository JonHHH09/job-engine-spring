package org.instruct.jobenginespring.application.profile;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Deterministic profile search use case over the normalized profile aggregate. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ProfileSearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9+#.]+", Pattern.CASE_INSENSITIVE);

    @NonNull
    private final ProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public ProfileSearchResult searchProfiles(ProfileSearchRequest request) {
        ProfileSearchRequest safeRequest = validate(request);
        List<String> queryTokens = tokens(safeRequest.query());
        List<ProfileSearchMatch> matches = profileRepository.listProfiles().stream()
                .map(UserProfile::id)
                .map(profileRepository::findProfileAggregate)
                .flatMap(java.util.Optional::stream)
                .map(aggregate -> match(aggregate, queryTokens))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(ProfileSearchMatch::score).reversed()
                        .thenComparing(match -> match.profile().fullName())
                        .thenComparing(match -> match.profile().id()))
                .limit(safeRequest.limit())
                .toList();
        return new ProfileSearchResult(safeRequest.query().strip(), queryTokens, matches.size(), matches);
    }

    private static ProfileSearchRequest validate(ProfileSearchRequest request) {
        if (request == null) {
            throw validation("request", "must not be null");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw validation("query", "must not be blank");
        }
        List<String> queryTokens = tokens(request.query());
        if (queryTokens.isEmpty()) {
            throw validation("query", "must contain searchable text");
        }
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw validation("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return new ProfileSearchRequest(request.query().strip(), limit);
    }

    private static ProfileSearchMatch match(ProfileAggregate aggregate, List<String> queryTokens) {
        Set<String> matchedFields = new LinkedHashSet<>();
        int score = 0;
        UserProfile profile = aggregate.profile();
        score += scoreText(queryTokens, "profile.fullName", profile.fullName(), 8, matchedFields);
        score += scoreText(queryTokens, "profile.email", profile.email(), 5, matchedFields);
        score += scoreText(queryTokens, "profile.summary", profile.summary(), 3, matchedFields);
        score += aggregate.contacts().stream()
                .mapToInt(contact -> scoreContact(queryTokens, contact, matchedFields))
                .sum();
        score += aggregate.links().stream()
                .mapToInt(link -> scoreLink(queryTokens, link, matchedFields))
                .sum();
        score += aggregate.skills().stream()
                .mapToInt(skill -> scoreSkill(queryTokens, skill, matchedFields))
                .sum();
        score += aggregate.languages().stream()
                .mapToInt(language -> scoreLanguage(queryTokens, language, matchedFields))
                .sum();
        score += aggregate.education().stream()
                .mapToInt(education -> scoreEducation(queryTokens, education, matchedFields))
                .sum();
        score += aggregate.experiences().stream()
                .mapToInt(experience -> scoreExperience(queryTokens, experience, matchedFields))
                .sum();
        score += aggregate.projects().stream()
                .mapToInt(project -> scoreProject(queryTokens, project, matchedFields))
                .sum();
        score += aggregate.projectTechnologies().stream()
                .mapToInt(technology -> scoreTechnology(queryTokens, technology, matchedFields))
                .sum();
        return new ProfileSearchMatch(profile, score, List.copyOf(matchedFields));
    }

    private static int scoreContact(List<String> queryTokens, ProfileContact contact, Set<String> matchedFields) {
        return scoreText(queryTokens, "contacts", contact.contactType(), 2, matchedFields)
                + scoreText(queryTokens, "contacts", contact.contactValue(), 2, matchedFields)
                + scoreText(queryTokens, "contacts", contact.label(), 1, matchedFields);
    }

    private static int scoreLink(List<String> queryTokens, ProfileLink link, Set<String> matchedFields) {
        return scoreText(queryTokens, "links", link.linkType(), 3, matchedFields)
                + scoreText(queryTokens, "links", link.url(), 3, matchedFields)
                + scoreText(queryTokens, "links", link.label(), 2, matchedFields);
    }

    private static int scoreSkill(List<String> queryTokens, ProfileSkill skill, Set<String> matchedFields) {
        return scoreText(queryTokens, "skills", skill.skill(), 7, matchedFields)
                + scoreText(queryTokens, "skills", skill.normalizedSkill(), 7, matchedFields)
                + scoreText(queryTokens, "skills.category", skill.category(), 3, matchedFields);
    }

    private static int scoreLanguage(List<String> queryTokens, ProfileLanguage language, Set<String> matchedFields) {
        return scoreText(queryTokens, "languages", language.language(), 4, matchedFields)
                + scoreText(queryTokens, "languages", language.normalizedLanguage(), 4, matchedFields)
                + scoreText(queryTokens, "languages", language.proficiency(), 2, matchedFields);
    }

    private static int scoreEducation(List<String> queryTokens, Education education, Set<String> matchedFields) {
        return scoreText(queryTokens, "education", education.institution(), 4, matchedFields)
                + scoreText(queryTokens, "education", education.degree(), 3, matchedFields)
                + scoreText(queryTokens, "education", education.field(), 4, matchedFields)
                + scoreText(queryTokens, "education", education.location(), 2, matchedFields)
                + scoreText(queryTokens, "education", education.relevantFocus(), 3, matchedFields);
    }

    private static int scoreExperience(List<String> queryTokens, Experience experience, Set<String> matchedFields) {
        return scoreText(queryTokens, "experience.company", experience.company(), 4, matchedFields)
                + scoreText(queryTokens, "experience.title", experience.title(), 6, matchedFields)
                + scoreText(queryTokens, "experience.location", experience.location(), 2, matchedFields)
                + scoreText(queryTokens, "experience.description", experience.description(), 3, matchedFields);
    }

    private static int scoreProject(List<String> queryTokens, ProfileProject project, Set<String> matchedFields) {
        return scoreText(queryTokens, "projects", project.name(), 5, matchedFields)
                + scoreText(queryTokens, "projects", project.url(), 3, matchedFields)
                + scoreText(queryTokens, "projects", project.description(), 3, matchedFields);
    }

    private static int scoreTechnology(List<String> queryTokens, ProjectTechnology technology, Set<String> matchedFields) {
        return scoreText(queryTokens, "projectTechnologies", technology.technology(), 5, matchedFields)
                + scoreText(queryTokens, "projectTechnologies", technology.normalizedTechnology(), 5, matchedFields);
    }

    private static int scoreText(List<String> queryTokens, String field, String text, int weight, Set<String> matchedFields) {
        Set<String> textTokens = new LinkedHashSet<>(tokens(text));
        if (textTokens.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String token : queryTokens) {
            if (textTokens.contains(token) || containsTokenPrefix(textTokens, token)) {
                hits++;
            }
        }
        if (hits > 0) {
            matchedFields.add(field);
        }
        return hits * weight;
    }

    private static boolean containsTokenPrefix(Set<String> textTokens, String queryToken) {
        return textTokens.stream().anyMatch(token -> token.startsWith(queryToken) || queryToken.startsWith(token));
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String[] rawTokens = TOKEN_SPLIT.split(normalized);
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(new LinkedHashSet<>(tokens));
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid profile search request",
                Map.of("field", field, "reason", reason),
                null
        );
    }

    public record ProfileSearchRequest(String query, Integer limit) {
    }

    public record ProfileSearchResult(
            String query,
            List<String> queryTokens,
            int totalMatches,
            List<ProfileSearchMatch> profiles
    ) {
        public ProfileSearchResult {
            queryTokens = queryTokens == null ? List.of() : List.copyOf(queryTokens);
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    public record ProfileSearchMatch(UserProfile profile, int score, List<String> matchedFields) {
        public ProfileSearchMatch {
            profile = Objects.requireNonNull(profile, "profile must not be null");
            matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        }
    }
}
