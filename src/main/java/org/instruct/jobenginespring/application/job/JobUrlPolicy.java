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

final class JobUrlPolicy {

    private static final Map<String, Set<String>> ATS_IDENTITY_PARAMETERS = Map.of(
            "greenhouse.io", Set.of("gh_jid"),
            "indeed.com", Set.of("jk")
    );
    private static final Set<String> GENERIC_IDENTITY_PARAMETERS = Set.of("id", "job_id", "jobid");
    private static final int MAX_IDENTITY_VALUE_LENGTH = 128;

    private JobUrlPolicy() {
    }

    static String safeDisplayUrl(String rawUrl) {
        URI uri = parse(rawUrl);
        return normalize(uri, List.of());
    }

    static String canonicalSourceUrl(String rawUrl) {
        URI uri = parse(rawUrl);
        return normalize(uri, safeIdentityParameters(uri.getHost(), uri.getRawQuery()));
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
        String path = rawPath.isEmpty() ? "/" : rawPath;
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
        if (query != null) {
            normalized.append('?').append(query);
        }
        return normalized.toString();
    }

    private static List<QueryParameter> safeIdentityParameters(String host, String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        Set<String> allowedParameters = identityParametersForHost(host);
        List<QueryParameter> safe = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);
            String normalizedName = normalizeParameterName(name);
            if (!allowedParameters.contains(normalizedName)) {
                continue;
            }
            String value = parts.length > 1 ? decode(parts[1]).strip() : "";
            if (!isSafeIdentityValue(value)) {
                continue;
            }
            safe.add(new QueryParameter(normalizedName, value));
        }
        return List.copyOf(safe);
    }

    private static Set<String> identityParametersForHost(String host) {
        String normalizedHost = normalizeParameterName(host);
        return ATS_IDENTITY_PARAMETERS.entrySet().stream()
                .filter(entry -> normalizedHost.equals(entry.getKey()) || normalizedHost.endsWith("." + entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(GENERIC_IDENTITY_PARAMETERS);
    }

    private static boolean isSafeIdentityValue(String value) {
        return value.length() <= MAX_IDENTITY_VALUE_LENGTH
                && value.matches("[A-Za-z0-9][A-Za-z0-9._~-]*");
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
