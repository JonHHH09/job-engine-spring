package org.instruct.jobenginespring.adapter.out.postgres.health;

import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthCheckResult;
import org.instruct.jobenginespring.application.health.DatabaseHealthService.DatabaseHealthErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresDatabaseHealthPortTests {

    private final JdbcOperations jdbcOperations = mock(JdbcOperations.class);
    private final PostgresDatabaseHealthPort port = new PostgresDatabaseHealthPort(jdbcOperations);

    @Test
    void executesSelectOneAndReportsUp() throws Exception {
        when(jdbcOperations.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        DatabaseHealthCheckResult result = port.checkSelectOne();

        assertTrue(result.successful());
        assertEquals(DatabaseHealthErrorCategory.NONE, result.errorCategory());

        verify(jdbcOperations).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void rejectsMissingJdbcOperations() {
        assertThrows(NullPointerException.class, () -> new PostgresDatabaseHealthPort(null));
    }
}
