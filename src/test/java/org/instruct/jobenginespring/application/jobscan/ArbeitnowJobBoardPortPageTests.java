package org.instruct.jobenginespring.application.jobscan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArbeitnowJobBoardPortPageTests {
    @Test
    void canonicalizesNullJobsToAnEmptyImmutableList() {
        ArbeitnowJobBoardPort.Page page = new ArbeitnowJobBoardPort.Page(null, true);

        assertTrue(page.jobs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> page.jobs().add(null));
        assertTrue(page.anotherPageMayRemain());
    }
}
