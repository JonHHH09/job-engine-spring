package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher.JobLinkFetchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobCoverageEdgeCaseTests {

    @Test
    void jobServiceFetchAndNormalizationPoliciesCoverAllSignals() throws Exception {
        Method blocked = method(JobService.class, "looksLikeBlockedOrSecurityCheck", String.class);
        assertFalse((boolean) blocked.invoke(null, new Object[]{null}));
        for (String signal : List.of(
                "security check", "additional verification required", "request blocked", "blocked - indeed",
                "cloudflare", "enable javascript", "javascript and cookies", "javascript is required", "app shell"
        )) {
            assertTrue((boolean) blocked.invoke(null, signal), signal);
        }

        Method validateFetched = method(JobService.class, "validateFetchedJobContent", JobLinkFetchResult.class);
        assertInvocationThrows(ApplicationException.class, validateFetched, (Object) null);
        validateFetched.invoke(null, new JobLinkFetchResult("https://example.test", null, null, null));

        Method normalize = method(JobService.class, "normalizeUrl", String.class);
        assertEquals("https://example.test/", normalize.invoke(null, "HTTPS://EXAMPLE.TEST"));
        assertEquals("http://example.test:8080/path", normalize.invoke(null, "http://EXAMPLE.test:8080/path/"));
        assertInvocationThrows(ApplicationException.class, normalize, "relative/path");
        assertInvocationThrows(ApplicationException.class, normalize, "ftp://example.test/job");

        Method mergeSkills = method(JobService.class, "mergeSkills", List.class, List.class);
        assertEquals(List.of(), mergeSkills.invoke(null, List.of(" "), null));
    }

    @Test
    void jobAnalysisPoliciesCoverNullsAndEveryBlockedContentSignal() throws Exception {
        Method blockedStatus = method(JobAnalysisService.class, "blockedContentFetchStatus", String.class);
        assertNull(blockedStatus.invoke(null, new Object[]{null}));
        for (String signal : List.of("request blocked", "blocked - indeed", "cloudflare")) {
            assertEquals("BLOCKED", blockedStatus.invoke(null, signal));
        }
        for (String signal : List.of(
                "security check", "additional verification required", "enable javascript",
                "javascript and cookies", "javascript is required", "app shell"
        )) {
            assertEquals("SECURITY_CHECK", blockedStatus.invoke(null, signal));
        }

        Method shell = method(JobAnalysisService.class, "looksLikeBotCheckOrJavaScriptShell", String.class);
        for (String signal : List.of(
                "enable javascript", "javascript is required", "javascript and cookies", "app shell",
                "security check", "additional verification required", "request blocked", "cloudflare"
        )) {
            assertTrue((boolean) shell.invoke(null, signal));
        }
        assertFalse((boolean) shell.invoke(null, "real job description"));

        Method url = method(JobAnalysisService.class, "looksLikeUrl", String.class);
        assertTrue((boolean) url.invoke(null, "http://example.test"));
        assertTrue((boolean) url.invoke(null, "https://example.test"));
        assertFalse((boolean) url.invoke(null, "Platform Engineer"));

        Method parseInstant = method(JobAnalysisService.class, "parseInstant", String.class);
        assertNull(parseInstant.invoke(null, new Object[]{null}));

        Method stringList = method(JobAnalysisService.class, "stringList", Object.class);
        assertEquals(List.of(), stringList.invoke(null, "not a list"));

        Method normalize = method(JobAnalysisService.class, "normalizeUrl", String.class);
        assertEquals("https://example.test/", normalize.invoke(null, "HTTPS://EXAMPLE.TEST"));
        assertEquals("http://example.test:8080/path", normalize.invoke(null, "http://EXAMPLE.test:8080/path/"));
        assertInvocationThrows(ApplicationException.class, normalize, "relative/path");
        assertInvocationThrows(ApplicationException.class, normalize, "ftp://example.test/job");

        Method provenance = method(JobAnalysisService.class, "validateFetchProvenance", String.class, Integer.class, String.class, String.class);
        assertEquals(List.of(), provenance.invoke(null, null, null, null, null));

        Method fetchStatus = method(JobAnalysisService.class, "fetchStatusFor", Integer.class, String.class, String.class);
        assertEquals("FETCHED", fetchStatus.invoke(null, null, null, null));

        Method analysisInput = method(JobAnalysisService.class, "analysisInput", String.class, String.class, JobLinkFetchResult.class);
        analysisInput.invoke(null, "https://example.test", "https://example.test/", null);

        Class<?> validationType = Class.forName(JobAnalysisService.class.getName() + "$FetchValidation");
        Constructor<?> constructor = validationType.getDeclaredConstructor(boolean.class, String.class, List.class);
        constructor.setAccessible(true);
        Object validation = constructor.newInstance(false, "FAILED", null);
        Method errors = validationType.getDeclaredMethod("validationErrors");
        errors.setAccessible(true);
        assertEquals(List.of(), errors.invoke(validation));
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void assertInvocationThrows(Class<? extends Throwable> expected, Method method, Object... arguments) {
        Exception exception = assertThrows(Exception.class, () -> method.invoke(null, arguments));
        assertTrue(expected.isInstance(exception.getCause()), () -> "Unexpected cause: " + exception.getCause());
    }
}
