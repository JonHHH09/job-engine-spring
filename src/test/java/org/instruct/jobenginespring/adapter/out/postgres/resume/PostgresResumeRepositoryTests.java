package org.instruct.jobenginespring.adapter.out.postgres.resume;

import org.instruct.jobenginespring.application.resume.port.ResumeRepository.EntryWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.SectionWrite;
import org.instruct.jobenginespring.application.resume.port.ResumeRepository.VariantWrite;
import org.instruct.jobenginespring.domain.resume.ResumeEntry;
import org.instruct.jobenginespring.domain.resume.ResumeEntryBullet;
import org.instruct.jobenginespring.domain.resume.ResumeSection;
import org.instruct.jobenginespring.domain.resume.ResumeVariant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PostgresResumeRepositoryTests {

    @Test
    void skipsEmptyNestedBatches() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);

        new PostgresResumeRepository(jdbc).batchInsertNestedContent(List.of());

        verifyNoInteractions(jdbc);
    }

    @Test
    void batchesEachNestedRowTypeInEncounterOrder() {
        NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
        PostgresResumeRepository repository = new PostgresResumeRepository(jdbc);
        UUID resumeId = UUID.randomUUID();
        VariantWrite first = variant(resumeId, "en", 0);
        VariantWrite second = variant(resumeId, "de", 1);

        repository.batchInsertNestedContent(List.of(first, second));

        ArgumentCaptor<SqlParameterSource[]> batches = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbc, times(4)).batchUpdate(any(String.class), batches.capture());
        assertArrayEquals(
                new Object[]{first.variant().id(), second.variant().id()},
                values(batches.getAllValues().get(0), "id")
        );
        assertArrayEquals(
                new Object[]{first.sections().getFirst().section().id(), second.sections().getFirst().section().id()},
                values(batches.getAllValues().get(1), "id")
        );
        assertArrayEquals(
                new Object[]{first.sections().getFirst().entries().getFirst().entry().id(), second.sections().getFirst().entries().getFirst().entry().id()},
                values(batches.getAllValues().get(2), "id")
        );
        assertArrayEquals(
                new Object[]{first.sections().getFirst().entries().getFirst().bullets().getFirst().id(), second.sections().getFirst().entries().getFirst().bullets().getFirst().id()},
                values(batches.getAllValues().get(3), "id")
        );
    }

    private static VariantWrite variant(UUID resumeId, String language, int order) {
        Instant now = Instant.EPOCH;
        ResumeVariant variant = new ResumeVariant(
                UUID.randomUUID(), resumeId, language, UUID.randomUUID(), language + ".pdf", now, now
        );
        ResumeSection section = new ResumeSection(UUID.randomUUID(), variant.id(), "experience", "Experience", order);
        ResumeEntry entry = new ResumeEntry(
                UUID.randomUUID(), section.id(), "experience", order, "Engineer", null, null, null, null, null
        );
        ResumeEntryBullet bullet = new ResumeEntryBullet(UUID.randomUUID(), entry.id(), order, "Built systems");
        return new VariantWrite(variant, List.of(new SectionWrite(section, List.of(new EntryWrite(entry, List.of(bullet))))));
    }

    private static Object[] values(SqlParameterSource[] parameters, String name) {
        return java.util.Arrays.stream(parameters).map(parameter -> parameter.getValue(name)).toArray();
    }
}
