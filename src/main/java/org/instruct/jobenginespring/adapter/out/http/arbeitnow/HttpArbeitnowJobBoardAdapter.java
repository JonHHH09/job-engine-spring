package org.instruct.jobenginespring.adapter.out.http.arbeitnow;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobBoardPort;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only Arbeitnow client whose endpoint and pagination links are strictly bounded. */
@Component
public class HttpArbeitnowJobBoardAdapter implements ArbeitnowJobBoardPort {
    private static final URI ENDPOINT = URI.create("https://www.arbeitnow.com/api/job-board-api");
    /** Enough for 100 bounded 4 KiB descriptions plus metadata, while rejecting unbounded payloads. */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_JOBS_PER_PAGE = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 4_096;
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int MAX_LIST_SIZE = 20;
    private static final int MAX_LIST_ELEMENT_LENGTH = 128;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String SAFE_MESSAGE = "Arbeitnow job board is temporarily unavailable";
    private static final long MAX_RETRY_AFTER_SECONDS = 86_400;
    private static final ObjectMapper STRICT_OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final HttpClient httpClient;
    private final Clock clock;

    public HttpArbeitnowJobBoardAdapter() {
        this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    HttpArbeitnowJobBoardAdapter(HttpClient httpClient) {
        this(httpClient, Clock.systemUTC());
    }

    HttpArbeitnowJobBoardAdapter(HttpClient httpClient, Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Page fetch(int page, Boolean visaSponsorship) {
        if (page < 1) {
            throw unavailable("http_status", false);
        }
        URI requestUri = requestUri(page, visaSponsorship);
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "job-engine-spring/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 429) {
                    throw rateLimited(response.headers().firstValue("retry-after").orElse(null));
                }
                if (response.statusCode() != 200) {
                    throw httpStatus(response.statusCode());
                }
                if (!isJson(response.headers().firstValue("content-type").orElse(null))) {
                    throw invalidResponse("content_type");
                }
                validateDeclaredLength(response.headers().firstValue("content-length").orElse(null));
                return parse(readBounded(body), page, visaSponsorship);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("transport", true);
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable("transport", true);
        }
    }

    private static URI requestUri(int page, Boolean visaSponsorship) {
        String query = "page=" + page + (visaSponsorship == null ? "" : "&visa_sponsorship=" + visaSponsorship);
        return URI.create(ENDPOINT + "?" + query);
    }

    private Page parse(byte[] body, int requestedPage, Boolean visaSponsorship) {
        JsonNode root;
        try {
            root = readStrictTree(body);
        } catch (RuntimeException exception) {
            throw invalidResponse("json");
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse("schema");
        }
        JsonNode data = required(root, "data");
        JsonNode links = required(root, "links");
        JsonNode meta = required(root, "meta");
        if (!data.isArray() || data.size() > MAX_JOBS_PER_PAGE || !links.isObject() || !meta.isObject()) {
            throw invalidResponse("schema");
        }
        int currentPage = positiveInt(required(meta, "current_page"));
        int lastPage = positiveInt(required(meta, "last_page"));
        if (currentPage != requestedPage || lastPage < currentPage) {
            throw invalidResponse("schema");
        }
        List<UpstreamJob> jobs = new ArrayList<>();
        for (JsonNode job : data) {
            jobs.add(mapJob(job));
        }
        JsonNode next = required(links, "next");
        boolean hasNext = !next.isNull();
        if (hasNext) {
            if (!next.isString()) {
                throw invalidResponse("pagination");
            }
            validateNext(next.stringValue(), requestedPage + 1, lastPage, visaSponsorship);
        } else if (lastPage != currentPage) {
            throw invalidResponse("pagination");
        }
        return new Page(jobs, hasNext);
    }

    private static JsonNode readStrictTree(byte[] body) {
        try (JsonParser parser = STRICT_OBJECT_MAPPER.createParser(body)) {
            return STRICT_OBJECT_MAPPER.readTree(parser);
        }
    }

    private static UpstreamJob mapJob(JsonNode job) {
        if (!job.isObject()) {
            throw invalidResponse("schema");
        }
        return new UpstreamJob(
                requiredStructuralSlug(job),
                requiredText(job, "company_name", MAX_TEXT_LENGTH),
                requiredText(job, "title", MAX_TEXT_LENGTH),
                requiredText(job, "description", MAX_DESCRIPTION_LENGTH),
                optionalBoolean(job, "remote"),
                requiredText(job, "url", MAX_DESCRIPTION_LENGTH),
                optionalTextList(job, "tags"),
                optionalTextList(job, "job_types"),
                optionalText(job, "location", MAX_TEXT_LENGTH),
                optionalEpoch(job, "created_at")
        );
    }

    private static void validateNext(String rawLink, int expectedPage, int lastPage, Boolean visaSponsorship) {
        if (expectedPage > lastPage || rawLink.length() > 2_048) {
            throw invalidResponse("pagination");
        }
        try {
            URI link = new URI(rawLink);
            if (!"https".equalsIgnoreCase(link.getScheme())
                    || !("www.arbeitnow.com".equalsIgnoreCase(link.getHost()) || "arbeitnow.com".equalsIgnoreCase(link.getHost()))
                    || (link.getPort() != -1 && link.getPort() != 443)
                    || !"/api/job-board-api".equals(link.getRawPath())
                    || link.getRawUserInfo() != null
                    || link.getRawFragment() != null) {
                throw invalidResponse("pagination");
            }
            Map<String, String> parameters = strictQuery(link.getRawQuery());
            if (!Integer.toString(expectedPage).equals(parameters.get("page"))
                    || !sponsorshipMatches(parameters.get("visa_sponsorship"), visaSponsorship)) {
                throw invalidResponse("pagination");
            }
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw invalidResponse("pagination");
        }
    }

    private static Map<String, String> strictQuery(String query) {
        if (query == null || query.isBlank()) {
            throw invalidResponse("pagination");
        }
        Map<String, String> result = new java.util.HashMap<>();
        for (String pair : query.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator != pair.lastIndexOf('=')) {
                throw invalidResponse("pagination");
            }
            String key = pair.substring(0, separator);
            String value = pair.substring(separator + 1);
            if (!(key.equals("page") || key.equals("visa_sponsorship")) || value.isEmpty() || result.putIfAbsent(key, value) != null) {
                throw invalidResponse("pagination");
            }
        }
        if (!result.containsKey("page")) {
            throw invalidResponse("pagination");
        }
        return Map.copyOf(result);
    }

    private static boolean sponsorshipMatches(String supplied, Boolean requested) {
        return requested == null ? supplied == null : Boolean.toString(requested).equals(supplied);
    }

    private static byte[] readBounded(InputStream body) throws IOException {
        if (body == null) {
            throw invalidResponse("body_size");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (read > MAX_RESPONSE_BYTES - output.size()) {
                throw invalidResponse("body_size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateDeclaredLength(String contentLength) {
        if (contentLength == null) {
            return;
        }
        try {
            if (Long.parseLong(contentLength) < 0 || Long.parseLong(contentLength) > MAX_RESPONSE_BYTES) {
                throw invalidResponse("body_size");
            }
        } catch (NumberFormatException exception) {
            throw invalidResponse("body_size");
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw invalidResponse("schema");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field, int maximumLength) {
        JsonNode value = required(parent, field);
        if (!value.isString()) {
            throw invalidResponse("schema");
        }
        return bounded(value.stringValue(), maximumLength);
    }

    private static String requiredStructuralSlug(JsonNode parent) {
        JsonNode value = required(parent, "slug");
        if (!value.isString() || value.stringValue().codePointCount(0, value.stringValue().length()) > MAX_TEXT_LENGTH) {
            throw invalidResponse("schema");
        }
        return value.stringValue();
    }

    private static String optionalText(JsonNode parent, String field, int maximumLength) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalidResponse("schema");
        }
        return bounded(value.stringValue(), maximumLength);
    }

    private static Boolean optionalBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalidResponse("schema");
        }
        return value.booleanValue();
    }

    private static Long optionalEpoch(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong() || !value.isIntegralNumber() || value.longValue() < 0) {
            throw invalidResponse("schema");
        }
        return value.longValue();
    }

    private static List<String> optionalTextList(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray() || value.size() > MAX_LIST_SIZE) {
            throw invalidResponse("schema");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isString()) {
                throw invalidResponse("schema");
            }
            values.add(bounded(element.stringValue(), MAX_LIST_ELEMENT_LENGTH));
        }
        return List.copyOf(values);
    }

    private static int positiveInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw invalidResponse("schema");
        }
        return value.intValue();
    }

    private static String bounded(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private ApplicationException rateLimited(String retryAfter) {
        Map<String, String> details = new java.util.HashMap<>(failureDetails("rate_limited", true));
        retryAfterSeconds(retryAfter).ifPresent(seconds -> details.put("retryAfterSeconds", Long.toString(seconds)));
        return new ApplicationException(ApplicationErrorCode.UPSTREAM_RATE_LIMITED, SAFE_MESSAGE, details, null);
    }

    private static ApplicationException httpStatus(int statusCode) {
        return unavailable("http_status", statusCode >= 500 && statusCode <= 599);
    }

    private static ApplicationException invalidResponse(String category) {
        return new ApplicationException(ApplicationErrorCode.UPSTREAM_INVALID_RESPONSE, SAFE_MESSAGE, failureDetails(category, false), null);
    }

    private static ApplicationException unavailable(String category, boolean retryable) {
        return new ApplicationException(ApplicationErrorCode.UPSTREAM_UNAVAILABLE, SAFE_MESSAGE, failureDetails(category, retryable), null);
    }

    private static Map<String, String> failureDetails(String category, boolean retryable) {
        return Map.of("provider", "arbeitnow", "failureCategory", category, "retryable", Boolean.toString(retryable));
    }

    private java.util.Optional<Long> retryAfterSeconds(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            long seconds = value.chars().allMatch(Character::isDigit) ? Long.parseLong(value) : Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return seconds >= 0 && seconds <= MAX_RETRY_AFTER_SECONDS ? java.util.Optional.of(seconds) : java.util.Optional.empty();
        } catch (RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }
}
