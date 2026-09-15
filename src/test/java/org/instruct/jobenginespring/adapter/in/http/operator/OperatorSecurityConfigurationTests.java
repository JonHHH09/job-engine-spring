package org.instruct.jobenginespring.adapter.in.http.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperatorSecurityConfigurationTests {

    private final OperatorSecurityConfiguration configuration = new OperatorSecurityConfiguration();

    @Test
    void defaultsToDisabledAndRejectsShortTokenWhenEnabled() {
        assertDoesNotThrow(() -> configuration.operatorSecurityFilter(false, "", false));
        assertThrows(IllegalStateException.class,
                () -> configuration.operatorSecurityFilter(true, "too-short", false));
        assertDoesNotThrow(() -> configuration.operatorSecurityFilter(true,
                "4rrxE1dNw81pp4YVwKcJ8Jf3xXR_0sTrhHXzToFwdYQ", false));
    }

    @Test
    void tokenLengthGateAppliesInTheContainerRuntimeToo() {
        assertThrows(IllegalStateException.class,
                () -> configuration.operatorSecurityFilter(true, "too-short", true));
        assertDoesNotThrow(() -> configuration.operatorSecurityFilter(true,
                "4rrxE1dNw81pp4YVwKcJ8Jf3xXR_0sTrhHXzToFwdYQ", true));
    }
}
