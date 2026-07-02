package org.instruct.jobenginespring.adapter.in.mcp.profile;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileMcpAdapterTests {

    @Test
    void exposesStableProfileCrudToolNames() {
        Set<String> toolNames = Arrays.stream(ProfileMcpAdapter.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "list_profiles",
                "get_profile",
                "create_profile",
                "update_profile",
                "delete_profile"
        ), toolNames);
    }

    @Test
    void createAndUpdateToolsDescribeRequestParameters() throws NoSuchMethodException {
        Method createProfile = ProfileMcpAdapter.class.getDeclaredMethod(
                "createProfile",
                org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest.class
        );
        Method updateProfile = ProfileMcpAdapter.class.getDeclaredMethod(
                "updateProfile",
                java.util.UUID.class,
                org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest.class
        );

        assertEquals("create_profile", createProfile.getAnnotation(McpTool.class).name());
        assertEquals("update_profile", updateProfile.getAnnotation(McpTool.class).name());
        assertEquals(1, createProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[0].length);
        assertEquals(1, updateProfile.getParameterAnnotations()[1].length);
    }
}
