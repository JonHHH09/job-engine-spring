package org.instruct.jobenginespring.adapter.out.postgres.job;

import org.instruct.jobenginespring.application.error.ApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PostgresJobAnalysisRunRepositoryJsonTests {

    private final PostgresJobAnalysisRunRepository repository =
            new PostgresJobAnalysisRunRepository(mock(JdbcClient.class));

    @Test
    void nullableAndMalformedJsonPathsAreHandledExplicitly() throws Exception {
        Method readMap = method("readMap", String.class);
        Method readNullableMap = method("readNullableMap", String.class);
        Method readStringList = method("readStringList", String.class);
        Method writeJson = method("writeJson", Object.class);
        Method writeNullableJson = method("writeNullableJson", Object.class);

        assertNull(readNullableMap.invoke(repository, new Object[]{null}));
        assertEquals(Map.of("key", "value"), readNullableMap.invoke(repository, "{\"key\":\"value\"}"));
        assertNull(writeNullableJson.invoke(repository, new Object[]{null}));
        assertEquals("{\"key\":\"value\"}", writeNullableJson.invoke(repository, Map.of("key", "value")));

        assertJsonFailure(readMap, "{");
        assertJsonFailure(readStringList, "{");
        assertJsonFailure(writeJson, new SelfReferencingValue());
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = PostgresJobAnalysisRunRepository.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private void assertJsonFailure(Method method, Object argument) {
        Exception exception = assertThrows(Exception.class, () -> method.invoke(repository, argument));
        assertTrue(exception.getCause() instanceof ApplicationException);
        assertEquals("Job analysis JSON serialization failed", ((ApplicationException) exception.getCause()).safeMessage());
    }

    private static final class SelfReferencingValue {
        public SelfReferencingValue getSelf() {
            return this;
        }
    }
}
