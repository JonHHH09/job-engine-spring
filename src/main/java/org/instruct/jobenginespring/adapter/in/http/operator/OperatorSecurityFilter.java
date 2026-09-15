package org.instruct.jobenginespring.adapter.in.http.operator;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

final class OperatorSecurityFilter extends OncePerRequestFilter {

    private static final int MAX_PATH_DECODING_PASSES = 64;
    private static final int MAX_AMBIGUOUS_PATH_LENGTH = 8_192;

    private final boolean enabled;
    private final byte[] expectedToken;

    OperatorSecurityFilter(boolean enabled, String bearerToken) {
        this.enabled = enabled;
        this.expectedToken = bearerToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestPath = pathWithinApplication(request);
        if (isAmbiguousOperatorPath(requestPath)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "validation_error", "Request validation failed");
            return;
        }
        String path = canonicalServletPath(request, requestPath);
        boolean operatorUi = isOperatorUiPath(path);
        boolean operatorApi = isOperatorApiPath(path);
        if (!operatorUi && !operatorApi) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (operatorUi) {
            applyBrowserHeaders(response);
        }
        if (!enabled) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Requested resource was not found");
            return;
        }
        if (!isExactLoopbackHost(request.getHeader(HttpHeaders.HOST))
                || !isLoopbackPeer(request)
                || !isSameOrigin(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "authorization_error", "Request is not authorized");
            return;
        }
        if (operatorApi && !hasExpectedBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "authorization_error", "Request is not authorized");
            return;
        }

        filterChain.doFilter(request, new SuppressNotFoundCommitResponse(response));
        if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND && !response.isCommitted()) {
            response.resetBuffer();
            reject(response, HttpServletResponse.SC_NOT_FOUND, "not_found", "Requested resource was not found");
        }
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
    }

    private String canonicalServletPath(HttpServletRequest request, String fallbackPath) {
        String servletPath = request.getServletPath();
        return servletPath == null || servletPath.isEmpty() ? fallbackPath : servletPath;
    }

    private boolean isAmbiguousOperatorPath(String rawPath) {
        if (!rawPath.contains(";") && !rawPath.contains("%")) {
            return false;
        }
        if (rawPath.length() > MAX_AMBIGUOUS_PATH_LENGTH) {
            return true;
        }
        String candidate = rawPath;
        try {
            for (int decodePass = 0; decodePass < MAX_PATH_DECODING_PASSES; decodePass++) {
                if (isOperatorPathAfterServletNormalization(candidate)) {
                    return true;
                }
                String decodedPath = URI.create("http://localhost" + candidate).getPath();
                if (decodedPath.equals(candidate)) {
                    return false;
                }
                candidate = decodedPath;
            }
            return isOperatorPathAfterServletNormalization(candidate) || candidate.contains("%");
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private boolean isOperatorPathAfterServletNormalization(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            int matrixParameter = segment.indexOf(';');
            String pathSegment = matrixParameter < 0 ? segment : segment.substring(0, matrixParameter);
            if (pathSegment.isEmpty() || ".".equals(pathSegment)) {
                continue;
            }
            if ("..".equals(pathSegment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(pathSegment);
        }
        String normalizedPath = "/" + String.join("/", segments);
        return isOperatorUiPath(normalizedPath) || isOperatorApiPath(normalizedPath);
    }

    private boolean isOperatorUiPath(String path) {
        return "/operator".equals(path) || path.startsWith("/operator/");
    }

    private boolean isOperatorApiPath(String path) {
        return "/api/operator".equals(path) || path.startsWith("/api/operator/");
    }

    private boolean hasExpectedBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] suppliedToken = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, suppliedToken);
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return true;
        }
        // The caller validates the Host header before evaluating an Origin.
        Host host = parseLoopbackHost(request.getHeader(HttpHeaders.HOST));
        String requestScheme = request.isSecure() ? "https" : "http";
        try {
            URI suppliedOrigin = URI.create(origin);
            return suppliedOrigin.getRawUserInfo() == null
                    && (suppliedOrigin.getRawPath() == null || suppliedOrigin.getRawPath().isEmpty())
                    && suppliedOrigin.getRawQuery() == null
                    && suppliedOrigin.getRawFragment() == null
                    && requestScheme.equalsIgnoreCase(suppliedOrigin.getScheme())
                    && host.name().equalsIgnoreCase(normalizeUriHost(suppliedOrigin.getHost()))
                    && host.portOrDefault(requestScheme) == portOrDefault(suppliedOrigin);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String normalizeUriHost(String host) {
        return "[::1]".equalsIgnoreCase(host) ? "::1" : host;
    }

    private int portOrDefault(URI origin) {
        if (origin.getPort() >= 0) {
            return origin.getPort();
        }
        return "https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80;
    }

    private boolean isLoopbackPeer(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isExactLoopbackHost(String value) {
        return parseLoopbackHost(value) != null;
    }

    private Host parseLoopbackHost(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String host;
        String portText = null;
        if (value.startsWith("[")) {
            int endBracket = value.indexOf(']');
            if (endBracket != 4 || !"[::1]".equalsIgnoreCase(value.substring(0, endBracket + 1))) {
                return null;
            }
            host = "::1";
            if (value.length() > endBracket + 1) {
                if (value.charAt(endBracket + 1) != ':') {
                    return null;
                }
                portText = value.substring(endBracket + 2);
            }
        } else {
            int separator = value.indexOf(':');
            if (separator >= 0 && separator != value.lastIndexOf(':')) {
                return null;
            }
            host = separator < 0 ? value : value.substring(0, separator);
            portText = separator < 0 ? null : value.substring(separator + 1);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!"localhost".equals(normalizedHost)
                && !"127.0.0.1".equals(normalizedHost)
                && !"::1".equals(normalizedHost)) {
            return null;
        }
        if (portText == null) {
            return new Host(normalizedHost, -1);
        }
        try {
            if (!portText.matches("[0-9]+")) {
                return null;
            }
            int port = Integer.parseInt(portText);
            return port >= 1 && port <= 65535 ? new Host(normalizedHost, port) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void applyBrowserHeaders(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
    }

    private void reject(HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"urn:job-engine:problem:" + code
                + "\",\"title\":\"" + title + "\",\"status\":" + status + "}");
    }

    private static final class SuppressNotFoundCommitResponse extends HttpServletResponseWrapper {
        private SuppressNotFoundCommitResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int status) {
            setStatus(status);
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            setStatus(status);
        }
    }

    private record Host(String name, int port) {
        int portOrDefault(String scheme) {
            return port >= 0 ? port : "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
    }
}
