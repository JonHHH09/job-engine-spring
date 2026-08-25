package org.instruct.jobenginespring.adapter.in.http.operator;

import org.junit.jupiter.api.Test;
import org.instruct.jobenginespring.application.error.ApplicationErrorCode;
import org.instruct.jobenginespring.application.error.ApplicationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperatorApiSecurityTests {

    private static final String TOKEN = "4rrxE1dNw81pp4YVwKcJ8Jf3xXR_0sTrhHXzToFwdYQ";

    @Test
    void rejectsOperatorApiWhenDisabled() throws Exception {
        operatorMvc(false).perform(get("/api/operator/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void rejectsAmbiguousMatrixAndEncodedOperatorApiPathsBeforeTheyReachTheController() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/api/operator;ignored/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/api/operator%3Bignored/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api%2Foperator/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOperatorPathsHiddenBehindFourOrMorePercentEncodingLayers() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/api%252525252Foperator/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/%252525252Foperator%252525253Bignored/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void leavesDeeplyEncodedNonOperatorPathsOutsideTheOperatorBoundary() throws Exception {
        operatorMvc(true).perform(get("/mcp%252525253Bignored").header(HttpHeaders.HOST, "evil.example"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void failsClosedWhenPercentDecodingExceedsTheWorkBudget() throws Exception {
        String path = "/mcp%3Bignored";
        for (int layer = 0; layer < 65; layer++) {
            path = path.replace("%", "%25");
        }
        operatorMvc(true).perform(get(path).header(HttpHeaders.HOST, "evil.example"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void permitsBenignMcpPathAtTheExactPercentDecodingBoundary() throws Exception {
        String path = "/mcp%3Bignored";
        for (int layer = 0; layer < 63; layer++) {
            path = path.replace("%", "%25");
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.addHeader(HttpHeaders.HOST, "evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainReached = new AtomicBoolean();
        new OperatorSecurityFilter(true, TOKEN).doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> filterChainReached.set(true));
        assertTrue(filterChainReached.get());
        org.junit.jupiter.api.Assertions.assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void rejectsOperatorPathAtTheExactPercentDecodingBoundary() throws Exception {
        String path = "/api%2Foperator/v1/ping";
        for (int layer = 0; layer < 63; layer++) {
            path = path.replace("%", "%25");
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean filterChainReached = new AtomicBoolean();
        new OperatorSecurityFilter(true, TOKEN).doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> filterChainReached.set(true));
        org.junit.jupiter.api.Assertions.assertFalse(filterChainReached.get());
        assertEquals(400, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void rejectsOversizedAndMalformedAmbiguousPathsBeforeRouting() throws Exception {
        for (String path : new String[]{"/api%2Foperator%ZZ", "/untrusted/" + "%25".repeat(2_800)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            request.addHeader(HttpHeaders.HOST, "evil.example");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean filterChainReached = new AtomicBoolean();
            new OperatorSecurityFilter(true, TOKEN).doFilter(request, response,
                    (ignoredRequest, ignoredResponse) -> filterChainReached.set(true));
            org.junit.jupiter.api.Assertions.assertFalse(filterChainReached.get());
            assertEquals(400, response.getStatus());
            assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        }
    }

    @Test
    void rejectsAmbiguousMatrixAndEncodedOperatorUiPathsBeforeTheyReachTheController() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/operator;ignored/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/operator%3Bignored/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void doesNotInterceptMcp() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/mcp;ignored").header(HttpHeaders.HOST, "evil.example"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
        MockHttpServletRequest malformedRequest = new MockHttpServletRequest("GET", "/mcp%ZZ");
        malformedRequest.setRequestURI("/mcp%ZZ");
        malformedRequest.addHeader(HttpHeaders.HOST, "evil.example");
        MockHttpServletResponse malformedResponse = new MockHttpServletResponse();
        AtomicBoolean filterChainReached = new AtomicBoolean();
        new OperatorSecurityFilter(true, TOKEN).doFilter(malformedRequest, malformedResponse,
                (request, response) -> filterChainReached.set(true));
        org.junit.jupiter.api.Assertions.assertFalse(filterChainReached.get());
        assertEquals(400, malformedResponse.getStatus());
        assertEquals("no-store", malformedResponse.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void rejectsMissingAndInvalidBearerTokens() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/api/operator/v1/ping").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permitsAValidBearerTokenOnlyFromExactLoopbackSameOrigin() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void permitsEnabledOperatorApiFromIpv6LoopbackWithDefaultEffectivePort() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "[::1]")
                        .header(HttpHeaders.ORIGIN, "http://[::1]:80")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .with(request -> { request.setRemoteAddr("::1"); return request; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void permitsEnabledOperatorUiFromIpv6LoopbackWithMatchingExplicitPort() throws Exception {
        operatorMvc(true).perform(get("/operator/")
                        .header(HttpHeaders.HOST, "[::1]:8080")
                        .header(HttpHeaders.ORIGIN, "http://[::1]:8080")
                        .with(request -> { request.setRemoteAddr("::1"); return request; }))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsIpv6OriginWithPortDifferentFromLoopbackHostPort() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "[::1]:8080")
                        .header(HttpHeaders.ORIGIN, "http://[::1]:8081")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .with(request -> { request.setRemoteAddr("::1"); return request; }))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsNonLoopbackHostAndCrossOriginRequests() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "evil.example")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void providesBrowserHeadersForOperatorPagesWithoutCors() throws Exception {
        operatorMvc(true).perform(get("/operator/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void mapsApplicationFailuresToSanitizedProblems() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/failure")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:job-engine:problem:validation_error"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    @Test
    void handlesMalformedJsonAndUnknownOperatorRoutesAsSanitizedProblems() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(post("/api/operator/v1/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
        mvc.perform(get("/api/operator/v1/unknown")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void disablesBothOperatorNamespacesIncludingNoTrailingSlashForms() throws Exception {
        MockMvc mvc = operatorMvc(false);
        mvc.perform(get("/operator").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/operator/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        mvc.perform(get("/api/operator").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void permitsLoopbackSameOriginBrowserPageWithoutBearerButRejectsCrossOrigin() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/operator/").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/operator/")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void rejectsOperatorRequestsFromNonLoopbackPeersWithoutTrustingForwardedHeaders() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("X-Forwarded-For", "127.0.0.1")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; }))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        operatorMvc(true).perform(get("/operator/")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; }))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidHostPortsAndOriginSchemeThatDoesNotMatchActualRequestOrigin() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1:65536")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:65536")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/operator/v1/ping")
                        .header(HttpHeaders.HOST, "127.0.0.1:8080")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:8081")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/operator/v1/ping")
                        .secure(true)
                        .header(HttpHeaders.HOST, "127.0.0.1:8443")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:8443")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/operator/v1/ping")
                        .secure(true)
                        .header(HttpHeaders.HOST, "127.0.0.1:8443")
                        .header(HttpHeaders.ORIGIN, "https://127.0.0.1:8443")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk());
    }

    @Test
    void mapsEveryApplicationErrorCodeAndUnexpectedFailuresToSanitizedProblems() throws Exception {
        MockMvc mvc = operatorMvc(true);
        Map<ApplicationErrorCode, Integer> expectedStatuses = Map.of(
                ApplicationErrorCode.VALIDATION_ERROR, 400,
                ApplicationErrorCode.AUTHORIZATION_ERROR, 403,
                ApplicationErrorCode.NOT_FOUND, 404,
                ApplicationErrorCode.CONFLICT, 409,
                ApplicationErrorCode.UPSTREAM_RATE_LIMITED, 429,
                ApplicationErrorCode.UPSTREAM_INVALID_RESPONSE, 502,
                ApplicationErrorCode.UPSTREAM_UNAVAILABLE, 502,
                ApplicationErrorCode.INTERNAL_ERROR, 500
        );
        for (ApplicationErrorCode code : ApplicationErrorCode.values()) {
            mvc.perform(get("/api/operator/v1/failure/" + code.name())
                            .header(HttpHeaders.HOST, "127.0.0.1")
                            .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                    .andExpect(status().is(expectedStatuses.get(code)))
                    .andExpect(jsonPath("$.type").value("urn:job-engine:problem:" + code.code()))
                    .andExpect(jsonPath("$.detail").value(code.defaultMessage()));
        }
        mvc.perform(get("/api/operator/v1/unexpected")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:job-engine:problem:internal_error"));
    }

    @Test
    void rejectsMalformedOperatorUrisAndEveryInvalidLoopbackHostShape() throws Exception {
        OperatorSecurityFilter filter = new OperatorSecurityFilter(true, TOKEN);
        for (String path : new String[]{"/operator%ZZ", "/api/operator%ZZ", "/safe/../operator;matrix/"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            request.addHeader(HttpHeaders.HOST, "127.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(400, response.getStatus(), path);
            assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        }
        for (String host : new String[]{null, "", "localhost:", "localhost:abc", "localhost:0", "localhost:65536",
                "localhost:999999999999999999999", "127.0.0.1:1:2", "[::1]x", "[::2]", "evil.example"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operator/");
            request.setRequestURI("/operator/");
            if (host != null) request.addHeader(HttpHeaders.HOST, host);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(403, response.getStatus(), String.valueOf(host));
        }
    }

    @Test
    void acceptsSecureLoopbackDefaultPortsAndExercisesParserEdgeCases() throws Exception {
        operatorMvc(true).perform(get("/api/operator/v1/ping")
                        .secure(true)
                        .header(HttpHeaders.HOST, "localhost")
                        .header(HttpHeaders.ORIGIN, "https://localhost")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk());

        OperatorSecurityFilter filter = new OperatorSecurityFilter(true, TOKEN);
        MockHttpServletRequest dottedPath = new MockHttpServletRequest("GET", "/operator%2F.%2F");
        dottedPath.setRequestURI("/operator%2F.%2F");
        dottedPath.addHeader(HttpHeaders.HOST, "127.0.0.1");
        MockHttpServletResponse dottedResponse = new MockHttpServletResponse();
        filter.doFilter(dottedPath, dottedResponse, (request, response) -> { });
        assertEquals(400, dottedResponse.getStatus());

        MockHttpServletRequest basicAuth = new MockHttpServletRequest("GET", "/api/operator/v1/ping");
        basicAuth.setRequestURI("/api/operator/v1/ping");
        basicAuth.addHeader(HttpHeaders.HOST, "127.0.0.1");
        basicAuth.addHeader(HttpHeaders.AUTHORIZATION, "Basic credentials");
        MockHttpServletResponse basicResponse = new MockHttpServletResponse();
        filter.doFilter(basicAuth, basicResponse, (request, response) -> { });
        assertEquals(401, basicResponse.getStatus());

        MockHttpServletRequest unresolvablePeer = new MockHttpServletRequest("GET", "/operator/");
        unresolvablePeer.setRequestURI("/operator/");
        unresolvablePeer.setRemoteAddr("not-a-valid-address");
        unresolvablePeer.addHeader(HttpHeaders.HOST, "127.0.0.1");
        MockHttpServletResponse peerResponse = new MockHttpServletResponse();
        filter.doFilter(unresolvablePeer, peerResponse, (request, response) -> { });
        assertEquals(403, peerResponse.getStatus());
    }

    @Test
    void handlesContextPathsOriginsAndFilterChainErrorWrappers() throws Exception {
        OperatorSecurityFilter filter = new OperatorSecurityFilter(true, TOKEN);
        MockHttpServletRequest contextRequest = new MockHttpServletRequest("GET", "/app/api/operator/v1/ping");
        contextRequest.setContextPath("/app");
        contextRequest.setRequestURI("/app/api/operator/v1/ping");
        contextRequest.setServletPath("");
        contextRequest.addHeader(HttpHeaders.HOST, "localhost");
        contextRequest.addHeader(HttpHeaders.AUTHORIZATION, bearerToken());
        MockHttpServletResponse contextResponse = new MockHttpServletResponse();
        filter.doFilter(contextRequest, contextResponse, (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertEquals(204, contextResponse.getStatus());

        for (String origin : new String[]{"http://127.0.0.1/path", "http://user@127.0.0.1", "http://127.0.0.1?x=y",
                "http://127.0.0.1#fragment", "not a uri"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operator/");
            request.setRequestURI("/operator/");
            request.addHeader(HttpHeaders.HOST, "127.0.0.1");
            request.addHeader(HttpHeaders.ORIGIN, origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(403, response.getStatus(), origin);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operator/");
        request.setRequestURI("/operator/");
        request.addHeader(HttpHeaders.HOST, "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, wrappedResponse) -> ((jakarta.servlet.http.HttpServletResponse) wrappedResponse).sendError(404, "hidden"));
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains("not_found"));

        MockHttpServletResponse responseWithoutMessage = new MockHttpServletResponse();
        filter.doFilter(request, responseWithoutMessage,
                (ignoredRequest, wrappedResponse) -> ((jakarta.servlet.http.HttpServletResponse) wrappedResponse).sendError(404));
        assertEquals(404, responseWithoutMessage.getStatus());
        assertTrue(responseWithoutMessage.getContentAsString().contains("not_found"));
    }

    @Test
    void sanitizesUnknownOperatorNamespaceRoutes() throws Exception {
        MockMvc mvc = operatorMvc(true);
        mvc.perform(get("/operator/unknown").header(HttpHeaders.HOST, "127.0.0.1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:job-engine:problem:not_found"));
        mvc.perform(get("/api/operator/unknown")
                        .header(HttpHeaders.HOST, "127.0.0.1")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:job-engine:problem:not_found"));
    }

    @Test
    void coversServletPathContextAndRemainingHostAndOriginParserBranches() throws Exception {
        OperatorSecurityFilter filter = new OperatorSecurityFilter(true, TOKEN);
        for (String contextPath : new String[]{"/app", "/other"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/operator/v1/ping");
            request.setContextPath(contextPath);
            request.setRequestURI("/app/api/operator/v1/ping");
            request.setServletPath("/api/operator/v1/ping");
            request.addHeader(HttpHeaders.HOST, "127.0.0.1");
            request.addHeader(HttpHeaders.AUTHORIZATION, bearerToken());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(200, response.getStatus());
        }
        MockHttpServletRequest nullContextAndServletPath = new MockHttpServletRequest("GET", "/api/operator/v1/ping") {
            @Override public String getContextPath() { return null; }
            @Override public String getServletPath() { return null; }
        };
        nullContextAndServletPath.setRequestURI("/api/operator/v1/ping");
        nullContextAndServletPath.addHeader(HttpHeaders.HOST, "127.0.0.1");
        nullContextAndServletPath.addHeader(HttpHeaders.AUTHORIZATION, bearerToken());
        MockHttpServletResponse nullPathResponse = new MockHttpServletResponse();
        filter.doFilter(nullContextAndServletPath, nullPathResponse, (ignoredRequest, ignoredResponse) -> { });
        assertEquals(200, nullPathResponse.getStatus());

        MockHttpServletRequest parentOnly = new MockHttpServletRequest("GET", "/..%2Foperator/");
        parentOnly.setRequestURI("/..%2Foperator/");
        parentOnly.addHeader(HttpHeaders.HOST, "127.0.0.1");
        MockHttpServletResponse parentResponse = new MockHttpServletResponse();
        filter.doFilter(parentOnly, parentResponse, (ignoredRequest, ignoredResponse) -> { });
        assertEquals(400, parentResponse.getStatus());

        for (String host : new String[]{"[", "[::1]:", "[::1]:abc"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operator/");
            request.setRequestURI("/operator/");
            request.addHeader(HttpHeaders.HOST, host);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(403, response.getStatus());
        }
        for (String origin : new String[]{"http:127.0.0.1", "http://localhost"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operator/");
            request.setRequestURI("/operator/");
            request.addHeader(HttpHeaders.HOST, "127.0.0.1");
            request.addHeader(HttpHeaders.ORIGIN, origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
            assertEquals(403, response.getStatus(), origin);
        }

        MockHttpServletRequest noOriginHost = new MockHttpServletRequest("GET", "/operator/");
        noOriginHost.setRequestURI("/operator/");
        noOriginHost.addHeader(HttpHeaders.HOST, "127.0.0.1");
        noOriginHost.addHeader(HttpHeaders.ORIGIN, "http:///");
        MockHttpServletResponse noOriginHostResponse = new MockHttpServletResponse();
        filter.doFilter(noOriginHost, noOriginHostResponse, (ignoredRequest, ignoredResponse) -> { });
        assertEquals(403, noOriginHostResponse.getStatus());

    }

    private static MockMvc operatorMvc(boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new OperatorFoundationController(), new TestOperatorController())
                .setControllerAdvice(new OperatorProblemHandler())
                .addFilters(new OperatorSecurityFilter(enabled, TOKEN))
                .build();
    }

    private static String bearerToken() {
        return "Bearer " + TOKEN;
    }

    @RestController
    static class TestOperatorController {

        @GetMapping("/mcp")
        @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        void mcp() {
        }

        @GetMapping("/api/operator/v1/failure")
        void failure() {
            throw new ApplicationException(ApplicationErrorCode.VALIDATION_ERROR, "Request validation failed");
        }

        @GetMapping("/api/operator/v1/failure/{code}")
        void failure(@PathVariable String code) {
            ApplicationErrorCode errorCode = ApplicationErrorCode.valueOf(code);
            throw new ApplicationException(errorCode, "untrusted message");
        }

        @GetMapping("/api/operator/v1/unexpected")
        void unexpected() {
            throw new IllegalStateException("untrusted message");
        }

        @PostMapping("/api/operator/v1/echo")
        Map<String, String> echo(@RequestBody EchoRequest request) {
            return Map.of("value", request.value());
        }

        record EchoRequest(String value) {
        }
    }
}
