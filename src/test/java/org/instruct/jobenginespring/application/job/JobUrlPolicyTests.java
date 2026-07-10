package org.instruct.jobenginespring.application.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobUrlPolicyTests {

    @Test
    void safeDisplayUrlStripsAllSensitiveUrlComponents() {
        assertEquals(
                "https://example.test/jobs/123",
                JobUrlPolicy.safeDisplayUrl("https://Example.test/jobs/123/?jk=job-123&token=secret#details")
        );
        assertEquals("https://example.test/", JobUrlPolicy.safeDisplayUrl("https://Example.test"));
    }

    @Test
    void canonicalSourceUrlKeepsOnlyExplicitIdentityParameters() {
        assertEquals(
                "https://example.test/jobs/view?gh_jid=gh-123&jk=job%20123",
                JobUrlPolicy.canonicalSourceUrl(
                        "https://Example.test/jobs/view/?utm_source=email&jk=job+123&empty=&gh_jid=gh-123&token=secret#details"
                )
        );
        assertEquals(
                "https://example.test/jobs/view",
                JobUrlPolicy.canonicalSourceUrl("https://example.test/jobs/view?utm_source=email&token=secret")
        );
    }

    @Test
    void rejectsUserinfoAndInvalidUrls() {
        ApplicationException userInfo = assertThrows(
                ApplicationException.class,
                () -> JobUrlPolicy.safeDisplayUrl("https://user:secret@example.test/jobs/123")
        );
        assertEquals("must not include userinfo", userInfo.details().get("reason"));

        ApplicationException invalid = assertThrows(
                ApplicationException.class,
                () -> JobUrlPolicy.canonicalSourceUrl("mailto:test@example.test")
        );
        assertEquals("must be an absolute http(s) URL", invalid.details().get("reason"));

        ApplicationException missingHost = assertThrows(
                ApplicationException.class,
                () -> JobUrlPolicy.canonicalSourceUrl("https:/missing-host")
        );
        assertEquals("must be an absolute http(s) URL", missingHost.details().get("reason"));

        ApplicationException relative = assertThrows(
                ApplicationException.class,
                () -> JobUrlPolicy.canonicalSourceUrl("example.test/jobs/123")
        );
        assertEquals("must be an absolute http(s) URL", relative.details().get("reason"));

        ApplicationException invalidSyntax = assertThrows(
                ApplicationException.class,
                () -> JobUrlPolicy.safeDisplayUrl("https://exa mple.test/jobs/123")
        );
        assertEquals("must be a valid absolute http(s) URL", invalidSyntax.details().get("reason"));
    }

    @Test
    void privateHelpersHandleNullAndBlankInputs() throws Exception {
        Constructor<JobUrlPolicy> constructor = JobUrlPolicy.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();

        Method safeIdentityParameters = JobUrlPolicy.class.getDeclaredMethod("safeIdentityParameters", String.class);
        Method normalizeParameterName = JobUrlPolicy.class.getDeclaredMethod("normalizeParameterName", String.class);
        Method decode = JobUrlPolicy.class.getDeclaredMethod("decode", String.class);
        Method encode = JobUrlPolicy.class.getDeclaredMethod("encode", String.class);
        Method clean = JobUrlPolicy.class.getDeclaredMethod("clean", String.class);
        Method normalize = JobUrlPolicy.class.getDeclaredMethod("normalize", URI.class, List.class);
        safeIdentityParameters.setAccessible(true);
        normalizeParameterName.setAccessible(true);
        decode.setAccessible(true);
        encode.setAccessible(true);
        clean.setAccessible(true);
        normalize.setAccessible(true);

        assertEquals(List.of(), safeIdentityParameters.invoke(null, new Object[]{null}));
        assertEquals(List.of(), safeIdentityParameters.invoke(null, " "));
        assertEquals(
                List.of(queryParameter("gh_jid", "job-1")),
                safeIdentityParameters.invoke(null, "&&jk&jk=&gh_jid=job-1")
        );
        assertEquals("", normalizeParameterName.invoke(null, new Object[]{null}));
        assertEquals("", decode.invoke(null, new Object[]{null}));
        assertEquals("", encode.invoke(null, new Object[]{null}));
        assertEquals(null, clean.invoke(null, new Object[]{null}));
        assertEquals(
                "https://example.test/path?a=1&b=2",
                normalize.invoke(null, URI.create("https://Example.test/path/"), List.of(
                        queryParameter("b", "2"),
                        queryParameter("a", "1")
                ))
        );
        assertEquals("https://example.test/?jk=job-1", normalize.invoke(null, URI.create("https://Example.test?jk=job-1"), List.of(
                queryParameter("jk", "job-1")
        )));
        assertEquals(
                "https://example.test:8443/path",
                normalize.invoke(null, URI.create("https://Example.test:8443/path"), List.of())
        );
    }

    private static Object queryParameter(String name, String value) throws Exception {
        Class<?> queryParameter = Class.forName("org.instruct.jobenginespring.application.job.JobUrlPolicy$QueryParameter");
        Constructor<?> constructor = queryParameter.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, value);
    }
}
