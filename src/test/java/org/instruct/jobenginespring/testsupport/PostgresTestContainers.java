package org.instruct.jobenginespring.testsupport;

import java.time.Duration;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgresTestContainers {

    private static final String READINESS_LOG_REGEX = ".*database system is ready to accept connections.*\\s";
    private static final int READINESS_LOG_COUNT = 2;
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(60);

    private PostgresTestContainers() {}

    public static PostgreSQLContainer postgres(String imageName) {
        return new PostgreSQLContainer(imageName)
                .withStartupAttempts(3)
                .waitingFor(readinessStrategy());
    }

    static WaitAllStrategy readinessStrategy() {
        WaitAllStrategy strategy = new WaitAllStrategy(WaitAllStrategy.Mode.WITH_OUTER_TIMEOUT)
                .withStartupTimeout(READINESS_TIMEOUT);
        strategy.withStrategy(new LogMessageWaitStrategy()
                .withRegEx(READINESS_LOG_REGEX)
                .withTimes(READINESS_LOG_COUNT));
        strategy.withStrategy(new HostPortWaitStrategy());
        return strategy;
    }
}
