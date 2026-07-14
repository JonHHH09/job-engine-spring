package org.instruct.jobenginespring.domain.document;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class StoredDocumentFileTests {

    @Test
    void providesCopyFreeReadAccessWithoutExposingMutableContent() throws IOException {
        byte[] input = "%PDF-private".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] expected = input.clone();
        StoredDocumentFile file = new StoredDocumentFile(
                UUID.randomUUID(), "resume.pdf", "application/pdf", input.length, "sha", input,
                Instant.EPOCH, Instant.EPOCH
        );

        input[0] = 'X';
        byte[] accessorCopy = file.content();
        accessorCopy[1] = 'X';

        assertArrayEquals(expected, file.openContentStream().readAllBytes());
        assertArrayEquals(expected, file.content());
    }
}
