package org.instruct.jobenginespring.adapter.out.http.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher.JobLinkFetchResult;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJobLinkContentFetcherTests {

    private static final String SAFE_URL = "http://93.184.216.34/job";

    @Test
    void fetchExtractsOpenGraphTitleMetaDescriptionAndStatus() {
        StaticHttpClient client = StaticHttpClient.ok(200, """
                <html><head>
                  <title>Fallback title</title>
                  <meta property="og:title" content="Platform Engineer">
                  <meta name="description" content="Build reliable systems">
                </head><body><script>ignore()</script><h1>Ignored H1</h1></body></html>
                """);

        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);

        assertEquals(SAFE_URL, result.url());
        assertEquals(URI.create(SAFE_URL), client.lastRequest.uri());
        assertEquals("Platform Engineer", result.title());
        assertEquals("Build reliable systems", result.description());
        assertEquals(200, result.httpStatus());
    }

    @Test
    void fetchFallsBackToVisibleTextAndH1Title() {
        StaticHttpClient client = StaticHttpClient.ok(
                201,
                "<html><body><h1>Backend Developer</h1><p>Skills: Java, Spring</p></body></html>"
        );

        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);

        assertEquals("Backend Developer", result.title());
        assertTrue(result.description().contains("Skills: Java, Spring"));
        assertEquals(201, result.httpStatus());
    }

    @Test
    void fetchAllowsMissingTitleAndTruncatesLongVisibleText() {
        StaticHttpClient client = StaticHttpClient.ok(200, "<html><body>" + "A".repeat(20_100) + "</body></html>");

        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);

        assertNull(result.title());
        assertEquals(20_000, result.description().length());
    }

    @Test
    void fetchDoesNotParsePastResponseByteLimit() {
        StaticHttpClient client = StaticHttpClient.ok(
                200,
                "<html><body>" + "A".repeat(140_000) + "<title>Too Late</title></body></html>"
        );

        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);

        assertNull(result.title());
        assertEquals(20_000, result.description().length());
    }

    @Test
    void fetchAllowsEmptyPagesWithoutTitleOrDescription() {
        StaticHttpClient client = StaticHttpClient.ok(200, "<html><body>   </body></html>");

        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);

        assertNull(result.title());
        assertNull(result.description());
    }

    @Test
    void fetchSkipsBlankTitleMatchesAndHandlesNullBody() {
        StaticHttpClient client = StaticHttpClient.ok(
                200,
                "<html><head><title> </title></head><body><h1>Fallback H1</h1></body></html>"
        );
        JobLinkFetchResult result = new HttpJobLinkContentFetcher(client).fetch(SAFE_URL);
        assertEquals("Fallback H1", result.title());

        JobLinkFetchResult nullBodyResult = new HttpJobLinkContentFetcher(StaticHttpClient.ok(204, null)).fetch(SAFE_URL);
        assertNull(nullBodyResult.description());
    }

    @Test
    void fetchReturnsSanitizedValidationErrorForBadUrlAndIoFailures() {
        ApplicationException badUrl = assertThrows(ApplicationException.class, () -> new HttpJobLinkContentFetcher().fetch("not a url"));
        assertEquals("validation_error", badUrl.errorCode().code());
        assertEquals("Invalid job link ingestion request", badUrl.safeMessage());

        ApplicationException ioFailure = assertThrows(ApplicationException.class, () -> new HttpJobLinkContentFetcher(new FailingHttpClient())
                .fetch(SAFE_URL));
        assertEquals("job link could not be fetched", ioFailure.details().get("reason"));
    }

    @Test
    void fetchRestoresInterruptFlagWhenClientIsInterrupted() {
        Thread.interrupted();
        HttpJobLinkContentFetcher fetcher = new HttpJobLinkContentFetcher(new InterruptingHttpClient());

        ApplicationException exception = assertThrows(ApplicationException.class, () -> fetcher.fetch(SAFE_URL));

        assertEquals("job link fetch was interrupted", exception.details().get("reason"));
        assertTrue(Thread.interrupted());
    }

    @Test
    void fetchRejectsLocalPrivateLinkLocalMulticastUnspecifiedAndMetadataHostsBeforeSend() {
        StaticHttpClient client = StaticHttpClient.ok(200, "<html><body>ok</body></html>");
        HttpJobLinkContentFetcher fetcher = new HttpJobLinkContentFetcher(client);

        List<String> blockedUrls = List.of(
                "http://localhost/job",
                "http://127.0.0.1/job",
                "http://[::1]/job",
                "http://10.0.0.10/job",
                "http://172.16.0.1/job",
                "http://192.168.1.50/job",
                "http://169.254.1.10/job",
                "http://224.0.0.1/job",
                "http://0.0.0.0/job",
                "http://169.254.169.254/latest/meta-data",
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://169.254.170.2/task",
                "http://100.100.100.200/latest/meta-data",
                "http://[fc00::1]/job",
                "http://user:password@93.184.216.34/job"
        );

        for (String blockedUrl : blockedUrls) {
            ApplicationException exception = assertThrows(
                    ApplicationException.class,
                    () -> fetcher.fetch(blockedUrl),
                    blockedUrl
            );
            assertEquals("validation_error", exception.errorCode().code());
        }
        assertEquals(0, client.sendCount.get());
    }

    @Test
    void fetchRejectsHostnamesBeforeSend() {
        StaticHttpClient client = StaticHttpClient.ok(200, "<html><body>ok</body></html>");
        HttpJobLinkContentFetcher fetcher = new HttpJobLinkContentFetcher(client, Set.of());

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> fetcher.fetch("http://example.test/job")
        );

        assertEquals("job link fetch requires an IP literal so address policy is connection-bound", exception.details().get("reason"));
        assertEquals(0, client.sendCount.get());
    }

    @Test
    void constructorTreatsNullAndBlankAllowedHostsAsEmptyConfig() {
        assertEquals(Set.of(), allowedHostsIn(new HttpJobLinkContentFetcher((String) null)));
        assertEquals(Set.of(), allowedHostsIn(new HttpJobLinkContentFetcher("   ")));
    }

    @Test
    void constructorParsesAllowedHostsByTrimmingLowercasingAndFilteringBlankEntries() {
        HttpJobLinkContentFetcher fetcher = new HttpJobLinkContentFetcher(
                " EXAMPLE.COM, ,\t,Jobs.Example.COM,, 93.184.216.34 "
        );

        assertEquals(Set.of("example.com", "jobs.example.com", "93.184.216.34"), allowedHostsIn(fetcher));
    }

    @Test
    void fetchStillRejectsParsedAllowedHostnameBeforeSend() {
        HttpJobLinkContentFetcher fetcher = new HttpJobLinkContentFetcher("example.test");

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> fetcher.fetch("http://example.test/job")
        );

        assertEquals("job link fetch requires an IP literal so address policy is connection-bound", exception.details().get("reason"));
    }

    @Test
    void fetchRejectsRedirectToPrivateTargetWithoutFollowingIt() {
        StaticHttpClient client = StaticHttpClient.response(
                302,
                "",
                Map.of("location", List.of("http://127.0.0.1/admin"))
        );

        ApplicationException exception = assertThrows(ApplicationException.class, () -> new HttpJobLinkContentFetcher(client).fetch(SAFE_URL));

        assertEquals("host resolves to a private or otherwise unsafe address", exception.details().get("reason"));
        assertEquals(1, client.sendCount.get());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> allowedHostsIn(HttpJobLinkContentFetcher fetcher) {
        try {
            java.lang.reflect.Field field = HttpJobLinkContentFetcher.class.getDeclaredField("allowedHosts");
            field.setAccessible(true);
            return (Set<String>) field.get(fetcher);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("allowedHosts field should be readable in tests", exception);
        }
    }

    private abstract static class BaseHttpClient extends HttpClient {
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StaticHttpClient extends BaseHttpClient {
        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;
        private final AtomicInteger sendCount = new AtomicInteger();
        private HttpRequest lastRequest;

        private StaticHttpClient(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        private static StaticHttpClient ok(int status, String body) {
            return response(status, body, Map.of());
        }

        private static StaticHttpClient response(int status, String body, Map<String, List<String>> headers) {
            return new StaticHttpClient(status, body, headers);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest = request;
            sendCount.incrementAndGet();
            Object responseBody = body == null ? null : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            return new StaticHttpResponse<>(status, request, headers, (T) responseBody);
        }
    }

    private static final class FailingHttpClient extends BaseHttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            throw new IOException("test failure");
        }
    }

    private static final class InterruptingHttpClient extends BaseHttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws InterruptedException {
            throw new InterruptedException("test interrupt");
        }
    }

    private record StaticHttpResponse<T>(
            int statusCode,
            HttpRequest request,
            Map<String, List<String>> rawHeaders,
            T body
    ) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(rawHeaders, (name, value) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
