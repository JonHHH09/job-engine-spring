package org.instruct.jobenginespring.adapter.out.postgres.profile;

import org.instruct.jobenginespring.domain.profile.ProfilePdfSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresProfilePdfSourceRepositoryTests {

    @Test
    void rejectsUnexpectedInsertCount() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(0);
        PostgresProfilePdfSourceRepository repository = new PostgresProfilePdfSourceRepository(jdbc);
        ProfilePdfSource source = new ProfilePdfSource(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "resume_pdf",
                Instant.parse("2026-07-14T12:00:00Z")
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> repository.save(source));

        assertEquals("Profile PDF source insert count was not one", exception.getMessage());
    }
}
