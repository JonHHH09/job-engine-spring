package org.instruct.jobenginespring.adapter.out.http.job;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "job-engine.job.link-fetcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpJobLinkContentFetcher implements JobLinkContentFetcher {

    private static final int MAX_BODY_CHARS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern META_DESCRIPTION = Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>");
    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "localhost.localdomain",
            "metadata.google.internal"
    );

    private final HttpClient httpClient;

    public HttpJobLinkContentFetcher() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    HttpJobLinkContentFetcher(HttpClient httpClient) {
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public JobLinkFetchResult fetch(String url) {
        try {
            URI uri = URI.create(url);
            validateSafeHttpUrl(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "job-engine-spring")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            validateRedirectTargetIfPresent(uri, response);
            String body = readLimitedBody(response.body());
            String title = firstMatch(body, OG_TITLE, TITLE, H1);
            String description = firstPresent(firstMatch(body, META_DESCRIPTION), visibleText(body));
            return new JobLinkFetchResult(url, title, truncate(description), response.statusCode());
        } catch (IOException exception) {
            throw validation("url", "job link could not be fetched");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw validation("url", "job link fetch was interrupted");
        } catch (IllegalArgumentException exception) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
    }

    private static void validateRedirectTargetIfPresent(URI sourceUri, HttpResponse<?> response) {
        int statusCode = response.statusCode();
        if (statusCode < 300 || statusCode > 399) {
            return;
        }
        Optional<String> location = response.headers().firstValue("location");
        if (location.isPresent()) {
            validateSafeHttpUrl(sourceUri.resolve(location.orElseThrow()));
        }
        throw validation("url", "job link redirects are not followed");
    }

    private static void validateSafeHttpUrl(URI uri) {
        if (!uri.isAbsolute()) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
        if (uri.getHost() == null) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
        String scheme = uri.getScheme();
        if (!Set.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost")) {
            throw unsafeHost();
        }
        if (!isIpLiteral(host)) {
            throw validation("url", "job link fetch requires an IP literal so address policy is connection-bound");
        }
        InetAddress address = InetAddress.ofLiteral(host);
        if (isUnsafeAddress(address)) {
            throw unsafeHost();
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":");
    }


    private static String readLimitedBody(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        try (body) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            int length = Math.min(bytes.length, MAX_RESPONSE_BYTES);
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || isCommonMetadataIpv4(address);
    }

    private static boolean isCommonMetadataIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        return Set.of("169.254.169.254", "169.254.170.2", "100.100.100.200")
                .contains(address.getHostAddress());
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (Byte.toUnsignedInt(address.getAddress()[0]) & 0xfe) == 0xfc;
    }

    private static ApplicationException unsafeHost() {
        return validation("url", "host resolves to a private or otherwise unsafe address");
    }

    private static String visibleText(String html) {
        String withoutScripts = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        String withoutTags = TAGS.matcher(withoutScripts).replaceAll(" ");
        String decoded = withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return decoded.replaceAll("\\s+", " ").strip();
    }

    @SafeVarargs
    private static String firstMatch(String body, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                String value = visibleText(matcher.group(1));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_BODY_CHARS).strip();
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(
                ApplicationErrorCode.VALIDATION_ERROR,
                "Invalid job link ingestion request",
                Map.of("field", field, "reason", reason),
                null
        );
    }
}
