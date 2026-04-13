package io.camunda.bizsol.bb.comm_agent.util;

import static io.camunda.bizsol.bb.comm_agent.util.ToolCallAssert.assertThatToolCalls;
import static io.camunda.bizsol.bb.comm_agent.util.ToolCallAssert.parameterSatisfying;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ToolCallAssertTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldAssertToolCallsFromJsonNode() throws Exception {
        JsonNode payload = objectMapper.readTree(loadFixture("fixtures/agentContext.json"));

        assertThatCode(
                        () ->
                                assertThatToolCalls(payload)
                                        .hasToolCallCount(2)
                                        .hasToolCallAtIndex(0, "Tool_StartBusinessAgent")
                                        .hasToolCallArgument(
                                                0, "customerDesireToPassToTheAgent", "Need help")
                                        .hasToolCallAtIndex(1, "Tool_StartBusinessAgent")
                                        .containsOnlyToolCallsNamed("Tool_StartBusinessAgent"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAssertToolCallsFromRawJson() throws Exception {
        String rawJson = loadFixture("fixtures/agentContext.json");

        assertThatCode(
                        () ->
                                assertThatToolCalls(rawJson)
                                        .hasToolCallCount(2)
                                        .containsToolCallNamed("Tool_StartBusinessAgent")
                                        .hasToolCallArgument(
                                                1, "customerDesireToPassToTheAgent", "Need help"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAssertToolCallParameterWithMatcher() throws Exception {
        JsonNode payload = objectMapper.readTree(loadFixture("fixtures/agentContext.json"));

        assertThatCode(
                        () ->
                                assertThatToolCalls(payload)
                                        .hasToolCallWithParameterMatching(
                                                "Tool_StartBusinessAgent",
                                                "customerDesireToPassToTheAgent",
                                                parameter ->
                                                        parameter.isTextual()
                                                                && parameter
                                                                        .asText()
                                                                        .contains("Need"))
                                        .hasToolCallWithParameterSatisfying(
                                                "Tool_StartBusinessAgent",
                                                parameterSatisfying(
                                                        "customerDesireToPassToTheAgent",
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .startsWith("Need"))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAssertMultipleParametersWithAllAssertionsPassing() throws Exception {
        JsonNode payload =
                objectMapper.readTree(
                        """
                        {
                          "toolCalls": [
                            {
                              "name": "Tool_NotifyBusinessAgent",
                              "arguments": {
                                "customerMessageInFull": "Need help with invoice #123",
                                "customerIntent": "billing support"
                              }
                            }
                          ]
                        }
                        """);

        assertThatCode(
                        () ->
                                assertThatToolCalls(payload)
                                        .hasToolCallWithParameterSatisfying(
                                                "Tool_NotifyBusinessAgent",
                                                parameterSatisfying(
                                                        "customerMessageInFull",
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .contains("invoice"),
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .startsWith("Need")),
                                                parameterSatisfying(
                                                        "customerIntent",
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .contains("billing"))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailWhenAnyParameterAssertionSpecFails() throws Exception {
        JsonNode payload =
                objectMapper.readTree(
                        """
                        {
                          "toolCalls": [
                            {
                              "name": "Tool_NotifyBusinessAgent",
                              "arguments": {
                                "customerMessageInFull": "Need help with invoice #123",
                                "customerIntent": "billing support"
                              }
                            }
                          ]
                        }
                        """);

        assertThatThrownBy(
                        () ->
                                assertThatToolCalls(payload)
                                        .hasToolCallWithParameterSatisfying(
                                                "Tool_NotifyBusinessAgent",
                                                parameterSatisfying(
                                                        "customerMessageInFull",
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .startsWith("Need")),
                                                parameterSatisfying(
                                                        "customerIntent",
                                                        parameter ->
                                                                assertThat(parameter.asText())
                                                                        .contains("technical"))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Parameter assertions failed");
    }

    private String loadFixture(String classpathLocation) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ToolCallAssertTest.class.getClassLoader();
        }
        try (InputStream inputStream = classLoader.getResourceAsStream(classpathLocation)) {
            if (inputStream == null) {
                throw new IllegalStateException("Fixture not found: " + classpathLocation);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
