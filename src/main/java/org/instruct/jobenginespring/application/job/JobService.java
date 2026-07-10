package org.instruct.jobenginespring.application.job;

import org.apache.commons.codec.digest.DigestUtils;
import lombok.NonNull;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher.JobLinkFetchResult;
import org.instruct.jobenginespring.application.job.port.JobRepository;
import org.instruct.jobenginespring.domain.job.JobAggregate;
import org.instruct.jobenginespring.domain.job.JobLinkIngestion;
import org.instruct.jobenginespring.domain.job.JobPosting;
import org.instruct.jobenginespring.domain.job.JobSkill;
import org.instruct.jobenginespring.domain.job.JobTextIngestion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobService {

    private static final String SOURCE_METHOD_LINK = "link";
    private static final String SOURCE_METHOD_TEXT = "text";
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int MAX_SEARCH_LIMIT = 100;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9+#.]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILLS_LINE = Pattern.compile("(?i)(?:required\\s+)?(?:technical\\s+)?skills?\\s*[:\\-]\\s*([^\\n.]+)");
    private static final Pattern EXPERIENCE_LINE = Pattern.compile("(?im)^(?:experience|requirements?|qualifications?)\\s*[:\\-]\\s*(.+)$");

    @NonNull
    private final JobRepository jobRepository;
    @NonNull
    private final JobLinkContentFetcher linkContentFetcher;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public JobService(JobRepository jobRepository, JobLinkContentFetcher linkContentFetcher) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.linkContentFetcher = Objects.requireNonNull(linkContentFetcher, "linkContentFetcher must not be null");
    }

    JobService(JobRepository jobRepository, JobLinkContentFetcher linkContentFetcher, Clock clock) {
        this(jobRepository, linkContentFetcher);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public List<JobPosting> listJobs() {
        return jobRepository.listJobs();
    }

    @Transactional(readOnly = true)
    public Optional<JobAggregate> getJob(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        return jobRepository.findJobAggregate(jobId);
    }

    @Transactional(readOnly = true)
    public JobSearchResult searchJobs(JobSearchRequest request) {
        JobSearchRequest safeRequest = validateSearch(request);
        List<String> queryTokens = tokens(safeRequest.query());
        List<JobSearchMatch> matches = jobRepository.listJobs().stream()
                .map(JobPosting::id)
                .map(jobRepository::findJobAggregate)
                .flatMap(Optional::stream)
                .map(aggregate -> match(aggregate, queryTokens))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(JobSearchMatch::score).reversed()
                        .thenComparing(match -> match.job().title())
                        .thenComparing(match -> match.job().id()))
                .limit(safeRequest.limit())
                .toList();
        return new JobSearchResult(safeRequest.query().strip(), queryTokens, matches.size(), matches);
    }

    @Transactional
    public JobAggregate updateJob(UpdateJobRequest request) {
        UpdateJobRequest safeRequest = validateUpdate(request);
        JobAggregate existing = jobRepository.findJobAggregate(safeRequest.jobId())
                .orElseThrow(() -> new JobNotFoundException(safeRequest.jobId()));
        JobPosting current = existing.job();
        String title = patchRequired(current.title(), safeRequest.title(), "title");
        String company = patchNullable(current.company(), safeRequest.company());
        String location = patchNullable(current.location(), safeRequest.location());
        String description = patchRequired(current.description(), safeRequest.description(), "description");
        Instant now = clock.instant();
        String canonicalFingerprint = fingerprint(title, company, location, description);
        rejectDuplicateUpdateFingerprint(current.id(), canonicalFingerprint);
        JobPosting updated = new JobPosting(
                current.id(),
                current.sourceMethod(),
                patchNullable(current.sourceLabel(), safeRequest.sourceLabel()),
                title,
                company,
                location,
                description,
                patchNullable(current.experienceRequirement(), safeRequest.experienceRequirement()),
                patchNullable(current.employmentType(), safeRequest.employmentType()),
                patchNullable(current.seniority(), safeRequest.seniority()),
                safeRequest.postedAt() == null ? current.postedAt() : safeRequest.postedAt(),
                canonicalFingerprint,
                current.createdAt(),
                now
        );
        List<JobSkill> updatedSkills = safeRequest.skills() == null ? existing.skills() : skills(current.id(), safeRequest.skills(), now);
        return jobRepository.updateJobAggregate(new JobAggregate(updated, updatedSkills, existing.linkIngestion(), existing.textIngestion()));
    }

    @Transactional
    public DeleteJobResult deleteJob(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        if (!jobRepository.deleteJob(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        return new DeleteJobResult(jobId, true);
    }

    @Transactional
    public AddJobResult addJobFromText(AddJobFromTextRequest request) {
        AddJobFromTextRequest safeRequest = validateTextRequest(request);
        String inputText = clean(safeRequest.text());
        String inputTextHash = sha256(canonicalWhitespace(inputText));
        Optional<JobAggregate> existingByText = jobRepository.findByInputTextHash(inputTextHash);
        if (existingByText.isPresent()) {
            return new AddJobResult("reused_existing_job", existingByText.orElseThrow());
        }

        String title = firstPresent(cleanToNull(safeRequest.title()), deriveTitle(inputText));
        String description = firstPresent(cleanToNull(safeRequest.description()), inputText);
        List<String> skills = mergeSkills(safeRequest.skills(), extractSkills(description));
        String experience = firstPresent(cleanToNull(safeRequest.experienceRequirement()), extractExperience(description));
        return saveIfNew(new DraftJob(
                SOURCE_METHOD_TEXT,
                cleanToNull(safeRequest.sourceLabel()),
                title,
                cleanToNull(safeRequest.company()),
                cleanToNull(safeRequest.location()),
                description,
                experience,
                cleanToNull(safeRequest.employmentType()),
                cleanToNull(safeRequest.seniority()),
                safeRequest.postedAt(),
                skills,
                null,
                new TextSource(cleanToNull(safeRequest.sourceLabel()), inputTextHash)
        ));
    }

    @Transactional
    public AddJobResult addJobFromLink(AddJobFromLinkRequest request) {
        AddJobFromLinkRequest safeRequest = validateLinkRequest(request);
        String normalizedUrl = normalizeUrl(safeRequest.url());
        Optional<JobAggregate> existingByUrl = jobRepository.findByNormalizedSourceUrl(normalizedUrl);
        if (existingByUrl.isPresent()) {
            return new AddJobResult("reused_existing_job", existingByUrl.orElseThrow());
        }

        JobLinkFetchResult fetched = linkContentFetcher.fetch(clean(safeRequest.url()));
        String explicitDescription = cleanToNull(safeRequest.description());
        if (explicitDescription == null) {
            validateFetchedJobContent(fetched);
        }
        String fetchedDescription = cleanToNull(fetched.description());
        String title = firstPresent(cleanToNull(safeRequest.title()), cleanToNull(fetched.title()), normalizedUrl);
        String description = firstPresent(explicitDescription, fetchedDescription, title);
        List<String> skills = mergeSkills(safeRequest.skills(), extractSkills(description));
        String experience = firstPresent(cleanToNull(safeRequest.experienceRequirement()), extractExperience(description));
        return saveIfNew(new DraftJob(
                SOURCE_METHOD_LINK,
                cleanToNull(safeRequest.sourceLabel()),
                title,
                cleanToNull(safeRequest.company()),
                cleanToNull(safeRequest.location()),
                description,
                experience,
                cleanToNull(safeRequest.employmentType()),
                cleanToNull(safeRequest.seniority()),
                safeRequest.postedAt(),
                skills,
                new LinkSource(normalizedUrl, normalizedUrl, fetched.httpStatus(), cleanToNull(fetched.title())),
                null
        ));
    }

    @Transactional
    public AddJobResult addJobFromAnalyzedLink(AddJobFromAnalyzedLinkRequest request) {
        AddJobFromAnalyzedLinkRequest safeRequest = validateAnalyzedLinkRequest(request);
        String persistedUrl = normalizeUrl(safeRequest.url());
        String normalizedUrl = normalizeUrl(safeRequest.normalizedUrl());
        Optional<JobAggregate> existingByUrl = jobRepository.findByNormalizedSourceUrl(normalizedUrl);
        if (existingByUrl.isPresent()) {
            return new AddJobResult("reused_existing_job", existingByUrl.orElseThrow());
        }
        String description = clean(safeRequest.description());
        List<String> skills = mergeSkills(safeRequest.skills(), extractSkills(description));
        String experience = firstPresent(cleanToNull(safeRequest.experienceRequirement()), extractExperience(description));
        return saveIfNew(new DraftJob(
                SOURCE_METHOD_LINK,
                cleanToNull(safeRequest.sourceLabel()),
                clean(safeRequest.title()),
                cleanToNull(safeRequest.company()),
                cleanToNull(safeRequest.location()),
                description,
                experience,
                cleanToNull(safeRequest.employmentType()),
                cleanToNull(safeRequest.seniority()),
                safeRequest.postedAt(),
                skills,
                new LinkSource(persistedUrl, normalizedUrl, safeRequest.httpStatus(), cleanToNull(safeRequest.sourceTitle())),
                null
        ));
    }

    private AddJobResult saveIfNew(DraftJob draft) {
        String fingerprint = fingerprint(draft);
        Optional<JobAggregate> existingByFingerprint = jobRepository.findByCanonicalFingerprint(fingerprint);
        if (existingByFingerprint.isPresent()) {
            return new AddJobResult("reused_existing_job", existingByFingerprint.orElseThrow());
        }
        Instant now = clock.instant();
        UUID jobId = UUID.randomUUID();
        JobPosting posting = new JobPosting(
                jobId,
                draft.sourceMethod(),
                draft.sourceLabel(),
                draft.title(),
                draft.company(),
                draft.location(),
                draft.description(),
                draft.experienceRequirement(),
                draft.employmentType(),
                draft.seniority(),
                draft.postedAt(),
                fingerprint,
                now,
                now
        );
        List<JobSkill> skills = skills(jobId, draft.skills(), now);
        JobLinkIngestion link = draft.linkSource() == null ? null : new JobLinkIngestion(
                UUID.randomUUID(),
                jobId,
                draft.linkSource().url(),
                draft.linkSource().normalizedUrl(),
                now,
                draft.linkSource().httpStatus(),
                draft.linkSource().sourceTitle(),
                now
        );
        JobTextIngestion text = draft.textSource() == null ? null : new JobTextIngestion(
                UUID.randomUUID(),
                jobId,
                draft.textSource().sourceLabel(),
                draft.textSource().inputTextHash(),
                now
        );
        JobAggregate saved = jobRepository.saveJobAggregate(new JobAggregate(posting, skills, link, text));
        String status = saved.job().id().equals(jobId) ? "created_job" : "reused_existing_job";
        return new AddJobResult(status, saved);
    }

    private void rejectDuplicateUpdateFingerprint(UUID currentJobId, String canonicalFingerprint) {
        jobRepository.findByCanonicalFingerprint(canonicalFingerprint)
                .filter(existing -> !existing.job().id().equals(currentJobId))
                .ifPresent(existing -> {
                    throw validation("canonicalFingerprint", "duplicates existing job " + existing.job().id());
                });
    }

    private static AddJobFromTextRequest validateTextRequest(AddJobFromTextRequest request) {
        if (request == null) {
            throw validation("request", "must not be null");
        }
        if (request.text() == null || request.text().isBlank()) {
            throw validation("text", "must not be blank");
        }
        return request;
    }

    private static AddJobFromLinkRequest validateLinkRequest(AddJobFromLinkRequest request) {
        if (request == null) {
            throw validation("request", "must not be null");
        }
        if (request.url() == null || request.url().isBlank()) {
            throw validation("url", "must not be blank");
        }
        normalizeUrl(request.url());
        return request;
    }

    private static AddJobFromAnalyzedLinkRequest validateAnalyzedLinkRequest(AddJobFromAnalyzedLinkRequest request) {
        if (request == null) {
            throw validation("request", "must not be null");
        }
        if (request.url() == null || request.url().isBlank()) {
            throw validation("url", "must not be blank");
        }
        if (request.normalizedUrl() == null || request.normalizedUrl().isBlank()) {
            throw validation("normalizedUrl", "must not be blank");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw validation("title", "must not be blank");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw validation("description", "must not be blank");
        }
        normalizeUrl(request.url());
        normalizeUrl(request.normalizedUrl());
        return request;
    }

    private static void validateFetchedJobContent(JobLinkFetchResult fetched) {
        if (fetched == null) {
            throw validation("fetchedContent", "job page fetch returned no content");
        }
        if (fetched.httpStatus() != null && fetched.httpStatus() >= 400) {
            throw validation("fetchedContent", "job page fetch returned HTTP " + fetched.httpStatus() + "; provide pasted job text or a usable description");
        }
        String combined = String.join(" ",
                Objects.toString(cleanToNull(fetched.title()), ""),
                Objects.toString(cleanToNull(fetched.description()), "")
        );
        if (looksLikeBlockedOrSecurityCheck(combined)) {
            throw validation("fetchedContent", "job page fetch returned blocked/security-check content; provide pasted job text or a usable description");
        }
    }

    private static boolean looksLikeBlockedOrSecurityCheck(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("security check")
                || lower.contains("additional verification required")
                || lower.contains("request blocked")
                || lower.contains("blocked - indeed")
                || lower.contains("cloudflare")
                || lower.contains("enable javascript")
                || lower.contains("javascript and cookies")
                || lower.contains("javascript is required")
                || lower.contains("app shell");
    }

    private static JobSearchRequest validateSearch(JobSearchRequest request) {
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
        int limit = request.limit() == null ? DEFAULT_SEARCH_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            throw validation("limit", "must be between 1 and " + MAX_SEARCH_LIMIT);
        }
        return new JobSearchRequest(request.query().strip(), limit);
    }

    private static UpdateJobRequest validateUpdate(UpdateJobRequest request) {
        if (request == null) {
            throw validation("request", "must not be null");
        }
        if (request.jobId() == null) {
            throw validation("jobId", "must not be null");
        }
        if (request.title() != null && request.title().isBlank()) {
            throw validation("title", "must not be blank");
        }
        if (request.description() != null && request.description().isBlank()) {
            throw validation("description", "must not be blank");
        }
        return request;
    }

    private static List<JobSkill> skills(UUID jobId, List<String> rawSkills, Instant createdAt) {
        List<String> uniqueSkills = mergeSkills(rawSkills, List.of());
        List<JobSkill> skills = new ArrayList<>();
        for (int index = 0; index < uniqueSkills.size(); index++) {
            String skill = uniqueSkills.get(index);
            skills.add(new JobSkill(UUID.randomUUID(), jobId, skill, normalizedKey(skill), true, index, createdAt));
        }
        return List.copyOf(skills);
    }

    private static JobSearchMatch match(JobAggregate aggregate, List<String> queryTokens) {
        Set<String> matchedFields = new LinkedHashSet<>();
        JobPosting job = aggregate.job();
        int score = scoreText(queryTokens, "job.title", job.title(), 8, matchedFields)
                + scoreText(queryTokens, "job.company", job.company(), 5, matchedFields)
                + scoreText(queryTokens, "job.location", job.location(), 3, matchedFields)
                + scoreText(queryTokens, "job.description", job.description(), 3, matchedFields)
                + scoreText(queryTokens, "job.experienceRequirement", job.experienceRequirement(), 4, matchedFields)
                + scoreText(queryTokens, "job.employmentType", job.employmentType(), 2, matchedFields)
                + scoreText(queryTokens, "job.seniority", job.seniority(), 2, matchedFields);
        score += aggregate.skills().stream()
                .mapToInt(skill -> scoreText(queryTokens, "job.skills", skill.skill(), 7, matchedFields))
                .sum();
        return new JobSearchMatch(job, aggregate.skills(), score, List.copyOf(matchedFields));
    }

    private static int scoreText(List<String> queryTokens, String field, String text, int weight, Set<String> matchedFields) {
        Set<String> textTokens = new LinkedHashSet<>(tokens(text));
        if (textTokens.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String token : queryTokens) {
            if (textTokens.contains(token) || textTokens.stream().anyMatch(textToken -> textToken.startsWith(token) || token.startsWith(textToken))) {
                hits++;
            }
        }
        if (hits > 0) {
            matchedFields.add(field);
        }
        return hits * weight;
    }

    private static List<String> extractSkills(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = SKILLS_LINE.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        return splitSkillList(matcher.group(1));
    }

    private static String extractExperience(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = EXPERIENCE_LINE.matcher(text);
        return matcher.find() ? cleanToNull(matcher.group(1)) : null;
    }

    private static String deriveTitle(String text) {
        return text.lines()
                .map(JobService::cleanToNull)
                .filter(Objects::nonNull)
                .filter(line -> line.length() <= 160)
                .findFirst()
                .orElse("Untitled Job");
    }

    private static List<String> mergeSkills(List<String> explicitSkills, List<String> extractedSkills) {
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        nullSafe(explicitSkills).stream().flatMap(skill -> splitSkillList(skill).stream()).forEach(skill -> skills.add(clean(skill)));
        nullSafe(extractedSkills).stream().flatMap(skill -> splitSkillList(skill).stream()).forEach(skill -> skills.add(clean(skill)));
        LinkedHashSet<String> normalizedSeen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        for (String skill : skills) {
            String normalized = normalizedKey(skill);
            if (normalizedSeen.add(normalized)) {
                unique.add(skill);
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> splitSkillList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("[,;|/]");
        List<String> skills = new ArrayList<>();
        for (String part : parts) {
            String skill = cleanToNull(part);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    private static String fingerprint(DraftJob draft) {
        return fingerprint(draft.title(), draft.company(), draft.location(), draft.description());
    }

    private static String fingerprint(String title, String company, String location, String description) {
        return sha256(String.join("\n",
                normalizedKey(title),
                normalizedKey(company),
                normalizedKey(location),
                canonicalWhitespace(description)
        ));
    }

    private static String normalizeUrl(String rawUrl) {
        try {
            URI uri = new URI(clean(rawUrl));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw validation("url", "must be an absolute http(s) URL");
            }
            String path = uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            URI normalized = new URI(scheme.toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT), uri.getPort(), path, null, null);
            return normalized.toString();
        } catch (URISyntaxException exception) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
    }

    private static String clean(String value) {
        return value == null ? null : value.strip();
    }

    private static String cleanToNull(String value) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String patchNullable(String current, String patch) {
        return patch == null ? current : cleanToNull(patch);
    }

    private static String patchRequired(String current, String patch, String fieldName) {
        if (patch == null) {
            return current;
        }
        String cleaned = cleanToNull(patch);
        if (cleaned == null) {
            throw validation(fieldName, "must not be blank");
        }
        return cleaned;
    }

    private static String canonicalWhitespace(String value) {
        return clean(value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizedKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String text) {
        String normalized = normalizedKey(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] rawTokens = TOKEN_SPLIT.split(normalized);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : rawTokens) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String sha256(String value) {
        return DigestUtils.sha256Hex(value);
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(ApplicationErrorCode.VALIDATION_ERROR, "Invalid job request", Map.of("field", field, "reason", reason), null);
    }

    public record AddJobFromTextRequest(
            String text,
            String sourceLabel,
            String title,
            String company,
            String location,
            String description,
            List<String> skills,
            String experienceRequirement,
            String employmentType,
            String seniority,
            Instant postedAt
    ) {
    }

    public record AddJobFromLinkRequest(
            String url,
            String sourceLabel,
            String title,
            String company,
            String location,
            String description,
            List<String> skills,
            String experienceRequirement,
            String employmentType,
            String seniority,
            Instant postedAt
    ) {
    }

    public record AddJobFromAnalyzedLinkRequest(
            String url,
            String normalizedUrl,
            String sourceLabel,
            String title,
            String company,
            String location,
            String description,
            List<String> skills,
            String experienceRequirement,
            String employmentType,
            String seniority,
            Instant postedAt,
            Integer httpStatus,
            String sourceTitle
    ) {
    }

    public record AddJobResult(String status, JobAggregate job) {
    }

    public record JobSearchRequest(String query, Integer limit) {
    }

    public record UpdateJobRequest(
            UUID jobId,
            String sourceLabel,
            String title,
            String company,
            String location,
            String description,
            List<String> skills,
            String experienceRequirement,
            String employmentType,
            String seniority,
            Instant postedAt
    ) {
    }

    public record DeleteJobResult(UUID jobId, boolean deleted) {
    }

    public record JobSearchResult(String query, List<String> queryTokens, int totalMatches, List<JobSearchMatch> jobs) {
        public JobSearchResult {
            queryTokens = queryTokens == null ? List.of() : List.copyOf(queryTokens);
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }
    }

    public record JobSearchMatch(JobPosting job, List<JobSkill> skills, int score, List<String> matchedFields) {
        public JobSearchMatch {
            Objects.requireNonNull(job, "job must not be null");
            skills = skills == null ? List.of() : List.copyOf(skills);
            matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        }
    }

    public static final class JobNotFoundException extends ApplicationException {
        public JobNotFoundException(UUID jobId) {
            super(ApplicationErrorCode.NOT_FOUND, "Job not found: " + jobId, Map.of("resource", "job", "jobId", String.valueOf(jobId)), null);
        }
    }

    private record DraftJob(
            String sourceMethod,
            String sourceLabel,
            String title,
            String company,
            String location,
            String description,
            String experienceRequirement,
            String employmentType,
            String seniority,
            Instant postedAt,
            List<String> skills,
            LinkSource linkSource,
            TextSource textSource
    ) {
    }

    private record LinkSource(String url, String normalizedUrl, Integer httpStatus, String sourceTitle) {
    }

    private record TextSource(String sourceLabel, String inputTextHash) {
    }
}
