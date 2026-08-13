package org.instruct.jobenginespring.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;

class PostgresTestContainersTests {

    @Test
    void factoryConfiguresThreeStartupAttempts() throws Exception {
        assertThat(field(PostgresTestContainers.postgres("postgres:17"), "startupAttempts"))
                .isEqualTo(3);
    }

    @Test
    void readinessPlanRequiresPostgresLogThenMappedHostPortWithinOneMinute() throws Exception {
        WaitAllStrategy readinessStrategy = PostgresTestContainers.readinessStrategy();
        List<WaitStrategy> readinessPlan = waitStrategies(readinessStrategy);

        assertThat(readinessPlan).hasSize(2);
        assertThat(readinessPlan.get(0)).isInstanceOf(LogMessageWaitStrategy.class);
        assertThat(field(readinessPlan.get(0), "regEx"))
                .isEqualTo(".*database system is ready to accept connections.*\\s");
        assertThat(field(readinessPlan.get(0), "times")).isEqualTo(2);
        assertThat(readinessPlan.get(1)).isInstanceOf(HostPortWaitStrategy.class);

        assertThat(field(readinessStrategy, "mode"))
                .isEqualTo(WaitAllStrategy.Mode.WITH_OUTER_TIMEOUT);
        assertThat(field(readinessStrategy, "timeout")).isEqualTo(Duration.ofSeconds(60));
        assertThat(waitStrategies(readinessStrategy)).containsExactlyElementsOf(readinessPlan);
    }

    @Test
    void readinessStrategiesAreIsolatedAcrossAssemblies() throws Exception {
        WaitAllStrategy first = PostgresTestContainers.readinessStrategy();
        WaitAllStrategy second = PostgresTestContainers.readinessStrategy();

        List<WaitStrategy> firstNestedStrategies = waitStrategies(first);
        List<WaitStrategy> secondNestedStrategies = waitStrategies(second);
        assertThat(first).isNotSameAs(second);
        assertThat(firstNestedStrategies).hasSize(2);
        assertThat(secondNestedStrategies).hasSize(2);
        assertThat(firstNestedStrategies.get(0)).isInstanceOf(LogMessageWaitStrategy.class);
        assertThat(firstNestedStrategies.get(1)).isInstanceOf(HostPortWaitStrategy.class);
        assertThat(secondNestedStrategies.get(0)).isInstanceOf(LogMessageWaitStrategy.class);
        assertThat(secondNestedStrategies.get(1)).isInstanceOf(HostPortWaitStrategy.class);
        assertThat(firstNestedStrategies.get(0)).isNotSameAs(secondNestedStrategies.get(0));
        assertThat(firstNestedStrategies.get(1)).isNotSameAs(secondNestedStrategies.get(1));
    }

    @SuppressWarnings("unchecked")
    private static List<WaitStrategy> waitStrategies(WaitAllStrategy strategy) throws ReflectiveOperationException {
        return (List<WaitStrategy>) field(strategy, "strategies");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
