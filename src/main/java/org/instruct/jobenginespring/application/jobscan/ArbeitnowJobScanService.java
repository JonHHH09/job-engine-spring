package org.instruct.jobenginespring.application.jobscan;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Read-only, bounded scan over the fixed Arbeitnow public job-board boundary. */
@Service
public class ArbeitnowJobScanService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int DEFAULT_MAX_PAGES = 1;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_PAGES = 5;
    private static final int MAX_QUERY_LENGTH = 256;
    private static final int MAX_QUERY_TERMS = 16;
    private static final int MAX_LOCATION_LENGTH = 128;
    private static final int MAX_FILTER_VALUES = 10;
    private static final int MAX_FILTER_VALUE_LENGTH = 128;
    private static final int MAX_EXCERPT = 1_000;
    private static final int MAX_SLUG_LENGTH = 256;
    private static final int MAX_CURSOR_PAGE = 1_000_000;
    private static final int MAX_CURSOR_OFFSET = 10_000;
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private final ArbeitnowJobBoardPort board;
    private final byte[] cursorKey;
    private final ArbeitnowCandidateTokenCodec candidateTokens;
    private final MacFactory macFactory;

    @Autowired
    public ArbeitnowJobScanService(ArbeitnowJobBoardPort board, ArbeitnowCandidateTokenCodec candidateTokens) {
        this(board, randomCursorKey(), candidateTokens);
    }

    ArbeitnowJobScanService(ArbeitnowJobBoardPort board) {
        this(board, randomCursorKey(), ArbeitnowCandidateTokenCodec.processLocal(Clock.systemUTC()));
    }

    ArbeitnowJobScanService(ArbeitnowJobBoardPort board, byte[] cursorKey) {
        this(board, cursorKey, ArbeitnowCandidateTokenCodec.processLocal(Clock.systemUTC()));
    }

    ArbeitnowJobScanService(ArbeitnowJobBoardPort board, byte[] cursorKey, ArbeitnowCandidateTokenCodec candidateTokens) {
        this(board, cursorKey, candidateTokens, () -> Mac.getInstance("HmacSHA256"));
    }

    ArbeitnowJobScanService(ArbeitnowJobBoardPort board, byte[] cursorKey, ArbeitnowCandidateTokenCodec candidateTokens, MacFactory macFactory) {
        this.board = Objects.requireNonNull(board);
        if (cursorKey == null || cursorKey.length < 32) {
            throw new IllegalArgumentException("Cursor key must contain at least 256 bits");
        }
        this.cursorKey = cursorKey.clone();
        this.candidateTokens = Objects.requireNonNull(candidateTokens);
        this.macFactory = Objects.requireNonNull(macFactory);
    }

    public ScanResult scan(ScanRequest raw) {
        ScanRequest request = raw == null ? emptyRequest() : raw;
        validate(request);
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        int maxPages = request.maxPages() == null ? DEFAULT_MAX_PAGES : request.maxPages();
        String fingerprint = fingerprint(request);
        Cursor cursor = request.cursor() == null ? new Cursor(1, 0, fingerprint) : decodeCursor(request.cursor(), fingerprint);

        int pageNumber = cursor.page();
        int offset = cursor.offset();
        int scanned = 0;
        int inspected = 0;
        List<ScannedJob> jobs = new ArrayList<>();
        boolean anotherPageMayRemain = false;

        while (true) {
            ArbeitnowJobBoardPort.Page page = board.fetch(pageNumber, request.visaSponsorship());
            scanned++;
            inspected += page.jobs().size();
            List<ArbeitnowJobBoardPort.UpstreamJob> matches = page.jobs().stream()
                    .filter(job -> matches(job, request))
                    .toList();
            if (offset > matches.size()) {
                throw invalid("cursor");
            }

            int available = matches.size() - offset;
            int take = Math.min(limit - jobs.size(), available);
            for (int index = 0; index < take; index++) {
                jobs.add(map(matches.get(offset + index)));
            }
            offset += take;
            anotherPageMayRemain = page.anotherPageMayRemain();

            if (jobs.size() == limit) {
                String next = offset < matches.size()
                        ? encode(new Cursor(pageNumber, offset, fingerprint))
                        : anotherPageMayRemain ? encode(new Cursor(pageNumber + 1, 0, fingerprint)) : null;
                return result(jobs, scanned, inspected, next);
            }
            if (!anotherPageMayRemain) {
                return result(jobs, scanned, inspected, null);
            }
            if (scanned == maxPages) {
                return result(jobs, scanned, inspected, encode(new Cursor(pageNumber + 1, 0, fingerprint)));
            }
            pageNumber++;
            offset = 0;
        }
    }

    private static ScanRequest emptyRequest() {
        return new ScanRequest(null, null, null, null, null, null, null, null, null);
    }

    private static ScanResult result(List<ScannedJob> jobs, int scanned, int inspected, String nextCursor) {
        return new ScanResult(
                "arbeitnow",
                "https://www.arbeitnow.com/api/job-board-api",
                List.copyOf(jobs),
                scanned,
                inspected,
                nextCursor,
                "Upstream results are mutable; this scan is not a snapshot."
        );
    }

    private boolean matches(ArbeitnowJobBoardPort.UpstreamJob job, ScanRequest request) {
        String searchable = lower(String.join(" ",
                safe(job.title()), safe(job.company()), safe(job.location()), plain(job.htmlDescription()),
                String.join(" ", job.tags()), String.join(" ", job.jobTypes())));
        for (String term : queryTerms(request.query())) {
            if (!searchable.contains(term)) {
                return false;
            }
        }
        if (request.location() != null && !lower(safe(job.location())).contains(lower(request.location().strip()))) {
            return false;
        }
        if (Boolean.TRUE.equals(request.remoteOnly()) && !Boolean.TRUE.equals(job.remote())) {
            return false;
        }
        return any(job.tags(), request.tags()) && any(job.jobTypes(), request.jobTypes());
    }

    private static boolean any(List<String> actual, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        return actual.stream().map(ArbeitnowJobScanService::lower)
                .anyMatch(value -> wanted.stream().map(valueToMatch -> lower(valueToMatch.strip())).anyMatch(value::equals));
    }

    private ScannedJob map(ArbeitnowJobBoardPort.UpstreamJob job) {
        String slug = safe(job.slug());
        if (slug.codePointCount(0, slug.length()) > MAX_SLUG_LENGTH) {
            throw invalidUpstreamData();
        }
        String canonicalUrl = canonical(slug);
        String company = plainBound(job.company(), 256);
        String title = plainBound(job.title(), 256);
        String location = plainBound(job.location(), 256);
        boolean remote = Boolean.TRUE.equals(job.remote());
        Instant postedAt = timestamp(job.createdAt());
        List<String> tags = boundList(job.tags());
        List<String> jobTypes = boundList(job.jobTypes());
        String description = bound(plain(job.htmlDescription()), MAX_EXCERPT);
        Instant issuedAt = candidateTokens.now();
        String candidateToken = candidateTokens.issue(new ArbeitnowCandidateTokenCodec.Candidate(
                "arbeitnow", issuedAt, issuedAt.plusSeconds(900), slug, canonicalUrl, company, title, location,
                remote, postedAt, tags, jobTypes, description));
        return new ScannedJob(slug, canonicalUrl, company, title, location, remote, postedAt, tags, jobTypes, description, candidateToken);
    }

    private static Instant timestamp(Long epochSecond) {
        if (epochSecond == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(epochSecond);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static String canonical(String slug) {
        if (!SLUG.matcher(slug).matches()) {
            throw invalidUpstreamData();
        }
        return ArbeitnowCandidateTokenCodec.canonical(slug);
    }

    private static List<String> boundList(List<String> values) {
        return values.stream()
                .map(value -> plainBound(value, 128))
                .filter(value -> !value.isBlank())
                .limit(20)
                .toList();
    }

    private static String plain(String html) {
        String text = HtmlUtils.htmlUnescape(safe(html));
        text = SCRIPT_STYLE.matcher(text).replaceAll(" ");
        text = TAGS.matcher(text).replaceAll(" ");
        text = text.replace('<', ' ').replace('>', ' ');
        text = CONTROL.matcher(text)
                .replaceAll(" ")
                .replace('\u00A0', ' ')
                .replace('\uFFFD', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        return bound(text, MAX_EXCERPT);
    }

    private static String plainBound(String value, int cap) {
        return bound(plain(value), cap);
    }

    private static String bound(String value, int cap) {
        String normalized = CONTROL.matcher(safe(value)).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (normalized.codePointCount(0, normalized.length()) <= cap) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, cap)).strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static void validate(ScanRequest request) {
        if (request.limit() != null && (request.limit() < 1 || request.limit() > MAX_LIMIT)
                || request.maxPages() != null && (request.maxPages() < 1 || request.maxPages() > MAX_PAGES)
                || request.query() != null && (request.query().length() > MAX_QUERY_LENGTH || queryTerms(request.query()).size() > MAX_QUERY_TERMS)
                || request.location() != null && request.location().length() > MAX_LOCATION_LENGTH
                || hasInvalidList(request.tags())
                || hasInvalidList(request.jobTypes())) {
            throw invalid("request");
        }
    }

    private static boolean hasInvalidList(List<String> values) {
        return values != null && (values.size() > MAX_FILTER_VALUES
                || values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > MAX_FILTER_VALUE_LENGTH));
    }

    private static List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(lower(query.strip()).split("\\s+"));
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid Arbeitnow scan " + field);
    }

    private static IllegalArgumentException invalidUpstreamData() {
        return new IllegalArgumentException("Invalid Arbeitnow upstream data");
    }

    private String fingerprint(ScanRequest request) {
        return java.util.HexFormat.of().formatHex(hmac(String.join("|",
                String.join(" ", queryTerms(request.query())),
                request.location() == null ? "" : lower(request.location().strip()),
                String.valueOf(request.remoteOnly()),
                String.valueOf(request.visaSponsorship()),
                fingerprintList(request.tags()),
                fingerprintList(request.jobTypes()))));
    }

    private static String fingerprintList(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .map(value -> lower(value.strip()))
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private String encode(Cursor cursor) {
        String payload = cursor.page() + ":" + cursor.offset() + ":" + cursor.fingerprint();
        String protectedPayload = payload + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(protectedPayload.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String token, String expectedFingerprint) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 4) {
                throw invalid("cursor");
            }
            int page = Integer.parseInt(parts[0]);
            int offset = Integer.parseInt(parts[1]);
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            byte[] suppliedMac = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expectedMac = hmac(payload);
            if (page < 1 || page > MAX_CURSOR_PAGE || offset < 0 || offset > MAX_CURSOR_OFFSET
                    || !MessageDigest.isEqual(suppliedMac, expectedMac) || !parts[2].equals(expectedFingerprint)) {
                throw invalid("cursor");
            }
            return new Cursor(page, offset, parts[2]);
        } catch (RuntimeException exception) {
            throw invalid("cursor");
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = macFactory.create();
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect cursor", exception);
        }
    }

    private static byte[] randomCursorKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @FunctionalInterface
    interface MacFactory {
        Mac create() throws Exception;
    }

    private record Cursor(int page, int offset, String fingerprint) {
    }

    public record ScanRequest(String query, String location, Boolean remoteOnly, Boolean visaSponsorship,
                              List<String> tags, List<String> jobTypes, Integer limit, Integer maxPages, String cursor) {
    }

    public record ScannedJob(String slug, String canonicalUrl, String company, String title, String location,
                             boolean remote, Instant postedAt, List<String> tags, List<String> jobTypes,
                             String descriptionExcerpt, String candidateToken) {
    }

    public record ScanResult(String source, String sourceDocsUrl, List<ScannedJob> jobs, int pagesScanned,
                             int recordsInspected, String nextCursor, String consistencyNotice) {
    }
}
