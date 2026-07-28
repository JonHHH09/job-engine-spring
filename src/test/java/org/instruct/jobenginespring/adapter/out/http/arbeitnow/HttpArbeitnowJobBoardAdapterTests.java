package org.instruct.jobenginespring.adapter.out.http.arbeitnow;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.jobscan.ArbeitnowJobBoardPort;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpArbeitnowJobBoardAdapterTests {
    private static final String BODY = """
            {"data":[{"slug":"platform-engineer","company_name":"Acme","title":"Platform Engineer","description":"<p>Build reliable systems</p>","remote":true,"url":"https://jobs.example.test/role?token=secret","tags":["Java","Spring"],"job_types":["Full-time"],"location":"Berlin","created_at":1710000000}],"links":{"next":"https://www.arbeitnow.com/api/job-board-api?page=2"},"meta":{"current_page":1,"last_page":2}}
            """;

    @Test
    void fetchBuildsOnlyFixedUriAndMapsBoundedJson() {
        StaticHttpClient client = StaticHttpClient.response(200, BODY, jsonHeaders());
        ArbeitnowJobBoardPort.Page result = new HttpArbeitnowJobBoardAdapter(client).fetch(1, null);

        assertEquals(URI.create("https://www.arbeitnow.com/api/job-board-api?page=1"), client.request.uri());
        assertEquals("application/json", client.request.headers().firstValue("Accept").orElseThrow());
        assertTrue(client.request.headers().firstValue("User-Agent").orElseThrow().contains("job-engine-spring"));
        assertTrue(result.anotherPageMayRemain());
        assertEquals(new ArbeitnowJobBoardPort.UpstreamJob("platform-engineer", "Acme", "Platform Engineer", "<p>Build reliable systems</p>", true, "https://jobs.example.test/role?token=secret", List.of("Java", "Spring"), List.of("Full-time"), "Berlin", 1710000000L), result.jobs().getFirst());
    }

    @Test
    void fetchUsesOnlyTypedVisaQueryValues() {
        for (Boolean visa : List.of(Boolean.TRUE, Boolean.FALSE)) {
            StaticHttpClient client = StaticHttpClient.response(200, terminalBody(), jsonHeaders());
            new HttpArbeitnowJobBoardAdapter(client).fetch(1, visa);
            assertEquals("page=1&visa_sponsorship=" + visa, client.request.uri().getRawQuery());
        }
    }

    @Test
    void fetchAcceptsJsonMediaTypesAndClassifiesInvalidAndUnavailableResponsesWithoutLeakingUpstreamData() {
        for (String contentType : List.of("Application/JSON; charset=UTF-8", "application/problem+json")) {
            assertFalse(new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, terminalBody(), Map.of("content-type", List.of(contentType)))).fetch(1, null).anotherPageMayRemain());
        }
        for (StaticHttpClient client : List.of(
                StaticHttpClient.response(200, "fixture-body-secret", Map.of()),
                StaticHttpClient.response(200, "fixture-body-secret", Map.of("content-type", List.of("text/html")))
        )) {
            ApplicationException exception = assertThrows(ApplicationException.class, () -> new HttpArbeitnowJobBoardAdapter(client).fetch(1, null));
            assertFailure(exception, "upstream_invalid_response", "content_type", false);
            assertTrue(client.body.closed);
        }
        assertFailure(fetchFailure(StaticHttpClient.response(302, "fixture-body-secret", Map.of("location", List.of("https://evil.test/?secret=query")))), "upstream_unavailable", "http_status", false);
        assertFailure(fetchFailure(StaticHttpClient.response(404, "fixture-body-secret", jsonHeaders())), "upstream_unavailable", "http_status", false);
        assertFailure(fetchFailure(StaticHttpClient.response(500, "fixture-body-secret", jsonHeaders())), "upstream_unavailable", "http_status", true);
    }

    @Test
    void fetchRejectsDeclaredAndStreamedOversizeNullBodyIoAndInterruptedFailures() {
        StaticHttpClient declared = StaticHttpClient.response(200, terminalBody(), Map.of("content-type", List.of("application/json"), "content-length", List.of("9999999")));
        assertFailure(fetchFailure(declared), "upstream_invalid_response", "body_size", false);
        assertTrue(declared.body.closed);

        StaticHttpClient streamed = StaticHttpClient.response(200, "x".repeat(524_289), jsonHeaders());
        assertFailure(fetchFailure(streamed), "upstream_invalid_response", "body_size", false);
        assertTrue(streamed.body.closed);

        assertFailure(fetchFailure(StaticHttpClient.nullBody()), "upstream_invalid_response", "body_size", false);
        assertFailure(fetchFailure(new FailingHttpClient()), "upstream_unavailable", "transport", true);
        assertFalse(Thread.interrupted());
        assertFailure(fetchFailure(new InterruptingHttpClient()), "upstream_unavailable", "transport", true);
        assertTrue(Thread.interrupted());
    }

    @Test
    void fetchRejectsMalformedJsonAndMalformedRequiredSchemaWithoutLeakingFixtureData() {
        for (String body : List.of(
                "fixture-body-secret",
                "{\"data\":{},\"links\":{},\"meta\":{}}",
                "{\"data\":[],\"links\":{},\"meta\":{\"current_page\":\"one\",\"last_page\":1}}",
                "{\"data\":[{\"slug\":1}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}"
        )) assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", body.equals("fixture-body-secret") ? "json" : "schema", false);
    }

    @Test
    void fetchRejectsDuplicateSchemaFieldAsSanitizedInvalidResponse() {
        String duplicateData = terminalBody().substring(0, terminalBody().length() - 1) + ",\"data\":[]}";

        assertFailure(fetchFailure(StaticHttpClient.response(200, duplicateData, jsonHeaders())), "upstream_invalid_response", "json", false);
    }

    @Test
    void fetchRejectsTrailingJsonValueAsSanitizedInvalidResponse() {
        assertFailure(fetchFailure(StaticHttpClient.response(200, terminalBody() + " []", jsonHeaders())), "upstream_invalid_response", "json", false);
    }

    @Test
    void fetchRejectsPositiveLastPageThatPrecedesTheRequestedCurrentPage() {
        String body = "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":2,\"last_page\":1}}";

        ApplicationException exception = assertThrows(ApplicationException.class,
                () -> new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, body, jsonHeaders())).fetch(2, null));

        assertFailure(exception, "upstream_invalid_response", "schema", false);
    }

    @Test
    void fetchRejectsMoreThanOneHundredJobsAndBoundsDescriptiveTextAndLists() {
        String minimalJob = "{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\"}";
        String tooManyJobs = "{\"data\":[" + String.join(",", java.util.Collections.nCopies(101, minimalJob)) + "],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";
        assertFailure(fetchFailure(StaticHttpClient.response(200, tooManyJobs, jsonHeaders())), "upstream_invalid_response", "schema", false);

        String bounded = "{\"data\":[{\"slug\":\"" + "s".repeat(256) + "\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"" + "d".repeat(4_097) + "\",\"url\":\"https://jobs.example.test/role\",\"tags\":[\"" + "t".repeat(129) + "\"],\"job_types\":[\"Full-time\"]}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";
        ArbeitnowJobBoardPort.UpstreamJob job = new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, bounded, jsonHeaders())).fetch(1, null).jobs().getFirst();
        assertEquals(256, job.slug().length());
        assertEquals(4_096, job.htmlDescription().length());
        assertEquals(128, job.tags().getFirst().length());
    }

    @Test
    void fetchRejectsOverlongStructuralSlugInsteadOfTruncatingItsIdentity() {
        String body = "{\"data\":[{\"slug\":\"" + "s".repeat(257) + "\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\"}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";

        assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", "schema", false);
    }

    @Test
    void fetchRejectsEveryUnsafeNextLinkDimensionAndAcceptsSafeNextLink() {
        for (String link : List.of(
                "http://www.arbeitnow.com/api/job-board-api?page=2",
                "https://evil.test/api/job-board-api?page=2",
                "https://www.arbeitnow.com/other?page=2",
                "https://www.arbeitnow.com:444/api/job-board-api?page=2",
                "https://user@www.arbeitnow.com/api/job-board-api?page=2",
                "https://www.arbeitnow.com/api/job-board-api?page=2#fragment",
                "https://www.arbeitnow.com/api/job-board-api?page=2&other=value",
                "https://www.arbeitnow.com/api/job-board-api?page=2&page=3",
                "https://www.arbeitnow.com/api/job-board-api?page=2&visa_sponsorship=true&visa_sponsorship=true",
                "https://www.arbeitnow.com/api/job-board-api?page=zero",
                "https://www.arbeitnow.com/api/job-board-api?page=3",
                "https://www.arbeitnow.com/api/job-board-api?page=2&visa_sponsorship=false"
        )) {
            ApplicationException exception = assertThrows(ApplicationException.class, () -> new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, pageWithNext(link), jsonHeaders())).fetch(1, Boolean.TRUE), link);
            assertFailure(exception, "upstream_invalid_response", "pagination", false);
        }
        assertTrue(new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, pageWithNext("https://WWW.ARBEITNOW.COM/api/job-board-api?page=2&visa_sponsorship=true"), jsonHeaders())).fetch(1, Boolean.TRUE).anotherPageMayRemain());
        assertTrue(new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, pageWithNext("https://arbeitnow.com/api/job-board-api?page=2&visa_sponsorship=true"), jsonHeaders())).fetch(1, Boolean.TRUE).anotherPageMayRemain());
        assertTrue(new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, pageWithNext("https://www.arbeitnow.com:443/api/job-board-api?page=2&visa_sponsorship=true"), jsonHeaders())).fetch(1, Boolean.TRUE).anotherPageMayRemain());
    }

    @Test
    void fetchRejectsRemainingPaginationAndSchemaVariants() {
        assertFailure(assertThrows(ApplicationException.class, () -> new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, terminalBody(), jsonHeaders())).fetch(0, null)), "upstream_unavailable", "http_status", false);
        for (String body : List.of(
                "[]",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":2,\"last_page\":2}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":2}}",
                "{\"data\":[],\"links\":{\"next\":1},\"meta\":{\"current_page\":1,\"last_page\":2}}",
                "{\"data\":[1],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}"
        )) assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", body.contains("\"next\":1") || body.contains("\"current_page\":1,\"last_page\":2") ? "pagination" : "schema", false);
        for (String link : List.of(
                "https://www.arbeitnow.com/api/job-board-api",
                "https://www.arbeitnow.com/api/job-board-api?page",
                "https://www.arbeitnow.com/api/job-board-api?page=2=3",
                "https://www.arbeitnow.com/api/job-board-api?visa_sponsorship=true",
                "https://www.arbeitnow.com/api/job-board-api?page=2&visa_sponsorship="
        )) assertFailure(fetchFailure(StaticHttpClient.response(200, pageWithNext(link), jsonHeaders())), "upstream_invalid_response", "pagination", false);
    }

    @Test
    void fetchRejectsOptionalFieldTypesAndMalformedDeclaredLength() {
        String base = "{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\"}";
        for (String replacement : List.of("\"remote\":\"true\"", "\"location\":1", "\"created_at\":-1", "\"created_at\":9223372036854775808", "\"tags\":1", "\"tags\":[1]", "\"job_types\":[1]")) {
            String job = base.substring(0, base.length() - 1) + "," + replacement + "}";
            assertFailure(fetchFailure(StaticHttpClient.response(200, "{\"data\":[" + job + "],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}", jsonHeaders())), "upstream_invalid_response", "schema", false);
        }
        for (String length : List.of("-1", "not-a-number")) {
            assertFailure(fetchFailure(StaticHttpClient.response(200, terminalBody(), Map.of("content-type", List.of("application/json"), "content-length", List.of(length)))), "upstream_invalid_response", "body_size", false);
        }
    }

    @Test
    void fetchAcceptsTheLargestIntegralCreatedAtValueThatTheProtocolCanRepresent() {
        String job = "{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\",\"created_at\":9223372036854775807}";
        String body = "{\"data\":[" + job + "],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";

        ArbeitnowJobBoardPort.UpstreamJob mapped = new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, body, jsonHeaders())).fetch(1, null).jobs().getFirst();

        assertEquals(Long.MAX_VALUE, mapped.createdAt());
    }

    @Test
    void fetchRejectsIntegralCreatedAtBelowJavaLongRangeAsSanitizedSchemaFailure() {
        String job = "{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\",\"created_at\":-9223372036854775809}";
        String body = "{\"data\":[" + job + "],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";

        assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", "schema", false);
    }

    @Test
    void optionalEpochRejectsADefensivelyInconsistentNonIntegralConvertibleNode() throws Exception {
        tools.jackson.databind.JsonNode parent = org.mockito.Mockito.mock(tools.jackson.databind.JsonNode.class);
        tools.jackson.databind.JsonNode inconsistent = org.mockito.Mockito.mock(tools.jackson.databind.JsonNode.class);
        org.mockito.Mockito.when(parent.get("created_at")).thenReturn(inconsistent);
        org.mockito.Mockito.when(inconsistent.canConvertToLong()).thenReturn(true);
        org.mockito.Mockito.when(inconsistent.isIntegralNumber()).thenReturn(false);
        var optionalEpoch = HttpArbeitnowJobBoardAdapter.class.getDeclaredMethod("optionalEpoch", tools.jackson.databind.JsonNode.class, String.class);
        optionalEpoch.setAccessible(true);

        java.lang.reflect.InvocationTargetException exception = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> optionalEpoch.invoke(null, parent, "created_at"));

        assertFailure((ApplicationException) exception.getCause(), "upstream_invalid_response", "schema", false);
    }

    @Test
    void fetchExercisesNullableOptionalValuesAndRemainingSchemaAndPaginationBoundaries() {
        String nullableOptionalJob = "{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"remote\":null,\"url\":\"https://jobs.example.test/role\",\"tags\":null,\"job_types\":null,\"location\":null,\"created_at\":null}";
        ArbeitnowJobBoardPort.UpstreamJob mapped = new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200,
                "{\"data\":[" + nullableOptionalJob + "],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}", jsonHeaders())).fetch(1, null).jobs().getFirst();
        assertEquals(List.of(), mapped.tags());
        assertEquals(List.of(), mapped.jobTypes());
        assertEquals(null, mapped.remote());
        assertEquals(null, mapped.location());
        assertEquals(null, mapped.createdAt());

        for (String body : List.of("   ",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":0}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":0,\"last_page\":1}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":1.5,\"last_page\":1}}")) {
            assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", "schema", false);
        }
        for (String link : List.of(
                "https://www.arbeitnow.com/api/job-board-api?page=2%",
                "https://www.arbeitnow.com/api/job-board-api?page=2&visa_sponsorship=false",
                "https://www.arbeitnow.com/api/job-board-api?page=2" + "x".repeat(2_000))) {
            assertFailure(fetchFailure(StaticHttpClient.response(200, pageWithNext(link), jsonHeaders())), "upstream_invalid_response", "pagination", false);
        }
        assertFalse(new HttpArbeitnowJobBoardAdapter(StaticHttpClient.response(200, terminalBody(), Map.of("content-type", List.of("application/json"), "content-length", List.of("0")))).fetch(1, null).anotherPageMayRemain());
        assertFailure(fetchFailure(StaticHttpClient.response(200, terminalBody(), Map.of("content-type", List.of("application/jsonx")))), "upstream_invalid_response", "content_type", false);
    }

    @Test
    void fetchExercisesRemainingShortCircuitSidesWithoutAcceptingMalformedData() {
        for (String body : List.of(
                "{\"data\":[],\"links\":[],\"meta\":{\"current_page\":1,\"last_page\":1}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":[]}",
                "{\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}",
                "{\"data\":[{\"slug\":\"slug\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\"}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}",
                "{\"data\":[{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\",\"created_at\":1.5}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}",
                "{\"data\":[{\"slug\":\"slug\",\"company_name\":\"Acme\",\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\",\"tags\":[" + "\"x\",".repeat(20) + "\"x\"]}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":2147483648,\"last_page\":1}}",
                "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":0,\"last_page\":1}}")) {
            assertFailure(fetchFailure(StaticHttpClient.response(200, body, jsonHeaders())), "upstream_invalid_response", "schema", false);
        }
        assertFailure(fetchFailure(StaticHttpClient.response(600, "fixture-body-secret", jsonHeaders())), "upstream_unavailable", "http_status", false);
        assertFailure(fetchFailure(StaticHttpClient.response(429, "fixture-body-secret", Map.of())), "upstream_rate_limited", "rate_limited", true);
        assertFailure(fetchFailure(StaticHttpClient.response(200, pageWithNext("https://www.arbeitnow.com/api/job-board-api?"), jsonHeaders())), "upstream_invalid_response", "pagination", false);
        assertFailure(fetchFailure(StaticHttpClient.response(200, "{\"data\":[],\"links\":{\"next\":\"https://www.arbeitnow.com/api/job-board-api?page=2\"},\"meta\":{\"current_page\":1,\"last_page\":1}}", jsonHeaders())), "upstream_invalid_response", "pagination", false);
        String nonTextCompany = "{\"data\":[{\"slug\":\"slug\",\"company_name\":1,\"title\":\"Engineer\",\"description\":\"Description\",\"url\":\"https://jobs.example.test/role\"}],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}";
        assertFailure(fetchFailure(StaticHttpClient.response(200, nonTextCompany, jsonHeaders())), "upstream_invalid_response", "schema", false);
        String overflowingEpoch = nonTextCompany.replace("\"company_name\":1", "\"company_name\":\"Acme\",\"created_at\":9223372036854775808");
        assertFailure(fetchFailure(StaticHttpClient.response(200, overflowingEpoch, jsonHeaders())), "upstream_invalid_response", "schema", false);
    }

    private static String terminalBody() { return "{\"data\":[],\"links\":{\"next\":null},\"meta\":{\"current_page\":1,\"last_page\":1}}"; }
    private static String pageWithNext(String link) { return "{\"data\":[],\"links\":{\"next\":\"" + link + "\"},\"meta\":{\"current_page\":1,\"last_page\":2}}"; }
    private static Map<String, List<String>> jsonHeaders() { return Map.of("content-type", List.of("application/json")); }
    @Test
    void fetchClassifies429AndParsesOnlyValidBoundedRetryAfterValuesWithoutSleeping() throws Exception {
        assertRetryAfter("120", Clock.systemUTC(), "120");
        assertRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", Clock.fixed(Instant.parse("2015-10-21T07:27:30Z"), ZoneOffset.UTC), "30");
        for (String value : List.of(" ", "-1", "999999999", "999999999999999999999999", "not-a-date", "Tue, 20 Oct 2015 07:28:00 GMT")) {
            ApplicationException exception = fetchFailure(adapterWithClock(StaticHttpClient.response(429, "fixture-body-secret", Map.of("retry-after", List.of(value))), Clock.fixed(Instant.parse("2015-10-21T07:27:30Z"), ZoneOffset.UTC)));
            assertFailure(exception, "upstream_rate_limited", "rate_limited", true);
            assertFalse(exception.details().containsKey("retryAfterSeconds"));
        }
    }

    private static void assertRetryAfter(String header, Clock clock, String expectedSeconds) throws Exception {
        ApplicationException exception = fetchFailure(adapterWithClock(StaticHttpClient.response(429, "fixture-body-secret", Map.of("retry-after", List.of(header))), clock));
        assertFailure(exception, "upstream_rate_limited", "rate_limited", true);
        assertEquals(expectedSeconds, exception.details().get("retryAfterSeconds"));
    }

    private static HttpArbeitnowJobBoardAdapter adapterWithClock(HttpClient client, Clock clock) throws Exception {
        var constructor = HttpArbeitnowJobBoardAdapter.class.getDeclaredConstructor(HttpClient.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(client, clock);
    }

    private static ApplicationException fetchFailure(HttpClient client) { return fetchFailure(new HttpArbeitnowJobBoardAdapter(client)); }
    private static ApplicationException fetchFailure(HttpArbeitnowJobBoardAdapter adapter) { return assertThrows(ApplicationException.class, () -> adapter.fetch(1, null)); }
    private static void assertFailure(ApplicationException exception, String code, String category, boolean retryable) {
        assertEquals(code, exception.errorCode().code());
        assertEquals("arbeitnow", exception.details().get("provider"));
        assertEquals(category, exception.details().get("failureCategory"));
        assertEquals(Boolean.toString(retryable), exception.details().get("retryable"));
        assertFalse(exception.safeMessage().contains("fixture-body-secret"));
        assertFalse(exception.safeMessage().contains("secret=query"));
        assertFalse(exception.safeMessage().contains("Exception"));
        assertFalse(exception.details().toString().contains("fixture-body-secret"));
        assertFalse(exception.details().toString().contains("secret=query"));
        assertFalse(exception.details().toString().contains("text/html"));
    }

    private abstract static class BaseHttpClient extends HttpClient {
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new IllegalStateException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
    }
    private static final class StaticHttpClient extends BaseHttpClient {
        private final int status; private final String text; private final Map<String, List<String>> headers; private final boolean nullBody; private CloseTrackingInputStream body; private HttpRequest request;
        private StaticHttpClient(int status, String text, Map<String, List<String>> headers, boolean nullBody) { this.status = status; this.text = text; this.headers = headers; this.nullBody = nullBody; }
        static StaticHttpClient response(int status, String text, Map<String, List<String>> headers) { return new StaticHttpClient(status, text, headers, false); }
        static StaticHttpClient nullBody() { return new StaticHttpClient(200, null, jsonHeaders(), true); }
        @SuppressWarnings("unchecked")
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> h) { this.request = request; body = nullBody ? null : new CloseTrackingInputStream(text.getBytes(StandardCharsets.UTF_8)); return new Response<>(status, request, headers, (T) body); }
    }
    private static final class FailingHttpClient extends BaseHttpClient { @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException { throw new IOException("fixture-body-secret"); } }
    private static final class InterruptingHttpClient extends BaseHttpClient { @Override public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) throws InterruptedException { throw new InterruptedException("fixture-body-secret"); } }
    private static final class CloseTrackingInputStream extends ByteArrayInputStream { boolean closed; CloseTrackingInputStream(byte[] bytes) { super(bytes); } @Override public void close() throws IOException { closed = true; super.close(); } }
    private record Response<T>(int statusCode, HttpRequest request, Map<String, List<String>> rawHeaders, T body) implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(rawHeaders, (_, _) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
