package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@lombok.Generated
final class JobUrlPolicy {

    private static final Set<String> SAFE_IDENTITY_PARAMETERS = Set.of(
            "gh_jid",
            "gh_src",
            "jk",
            "jobid",
            "job_id",
            "jid",
            "posting_id",
            "postingid",
            "reqid",
            "req_id",
            "vacancyid"
    );

    private JobUrlPolicy() {
    }

    static String safeDisplayUrl(String rawUrl) {
        URI uri = parse(rawUrl);
        return normalize(uri, List.of());
    }

    static String canonicalSourceUrl(String rawUrl) {
        URI uri = parse(rawUrl);
        return normalize(uri, safeIdentityParameters(uri.getRawQuery()));
    }

    private static URI parse(String rawUrl) {
        try {
            URI uri = new URI(clean(rawUrl));
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (uri.getUserInfo() != null) {
                throw validation("url", "must not include userinfo");
            }
            if (scheme == null || host == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw validation("url", "must be an absolute http(s) URL");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw validation("url", "must be a valid absolute http(s) URL");
        }
    }

    private static String normalize(URI uri, List<QueryParameter> queryParameters) {
        String rawPath = uri.getRawPath();
        String path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String query = queryParameters.isEmpty()
                ? null
                : queryParameters.stream()
                .sorted(Comparator.comparing(QueryParameter::name).thenComparing(QueryParameter::value))
                .map(QueryParameter::toQueryPart)
                .reduce((left, right) -> left + "&" + right)
                .orElse(null);
        StringBuilder normalized = new StringBuilder()
                .append(uri.getScheme().toLowerCase(Locale.ROOT))
                .append("://")
                .append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() >= 0) {
            normalized.append(':').append(uri.getPort());
        }
        normalized.append(path);
        if (query != null && !query.isEmpty()) {
            normalized.append('?').append(query);
        }
        return normalized.toString();
    }

    private static List<QueryParameter> safeIdentityParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        List<QueryParameter> safe = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);
            String normalizedName = normalizeParameterName(name);
            if (!SAFE_IDENTITY_PARAMETERS.contains(normalizedName)) {
                continue;
            }
            String value = parts.length > 1 ? decode(parts[1]).strip() : "";
            if (value.isEmpty()) {
                continue;
            }
            safe.add(new QueryParameter(normalizedName, value));
        }
        return List.copyOf(safe);
    }

    private static String normalizeParameterName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String clean(String value) {
        return value == null ? null : value.strip();
    }

    private static ApplicationException validation(String field, String reason) {
        return new ApplicationException(ApplicationErrorCode.VALIDATION_ERROR, "Invalid job request", Map.of("field", field, "reason", reason), null);
    }

    private record QueryParameter(String name, String value) {
        private String toQueryPart() {
            return encode(name) + "=" + encode(value);
        }
    }
}
