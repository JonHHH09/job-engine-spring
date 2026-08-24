package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.instruct.jobenginespring.application.job.port.JobLinkContentFetcher.JobLinkFetchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
