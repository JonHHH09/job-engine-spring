package org.instruct.jobenginespring.adapter.out.postgres.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "job-engine.health.postgres", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PostgresDatabaseHealthPort implements DatabaseHealthPort {

    private final JdbcOperations jdbcOperations;

    public PostgresDatabaseHealthPort(JdbcOperations jdbcOperations) {
        this.jdbcOperations = Objects.requireNonNull(jdbcOperations, "jdbcOperations must not be null");
    }

    @Override
    public DatabaseHealthCheckResult checkSelectOne() {
        jdbcOperations.queryForObject("SELECT 1", Integer.class);
        return DatabaseHealthCheckResult.up();
    }
}
