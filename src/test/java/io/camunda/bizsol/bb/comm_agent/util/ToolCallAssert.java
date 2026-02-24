package io.camunda.bizsol.bb.comm_agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ helper for validating tool calls emitted by an agent context payload.
 *
 * <p>Supported input shapes:
 *
 * <ul>
 *   <li>Root object containing {@code conversation.messages[*].toolCalls[*]}
 *   <li>Root object containing {@code toolCalls[*]}
 *   <li>Single tool call object ({@code {name, arguments, ...}})
 *   <li>Array with any of the above entries
 * </ul>
 */
public final class ToolCallAssert extends AbstractAssert<ToolCallAssert, JsonNode> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private final List<JsonNode> toolCalls;

    private ToolCallAssert(JsonNode actual) {
        super(actual, ToolCallAssert.class);
        toolCalls = extractToolCalls(actual);
    }

    /**
     * Start assertions from a parsed JSON payload.
     *
     * @param payload parsed JSON payload containing tool calls
     * @return fluent {@link ToolCallAssert}
     */
    public static ToolCallAssert assertThatToolCalls(JsonNode payload) {
        return new ToolCallAssert(payload);
    }

    /**
     * Start assertions from a raw JSON payload.
     *
     * @param rawJson raw JSON string containing tool calls
     * @return fluent {@link ToolCallAssert}
     */
    public static ToolCallAssert assertThatToolCalls(String rawJson) {
        assertThat(rawJson).withFailMessage("Expected a non-null raw JSON payload.").isNotNull();
        try {
            return new ToolCallAssert(OBJECT_MAPPER.readTree(rawJson));
        } catch (JsonProcessingException e) {
            throw new AssertionError("Unable to parse raw JSON payload.", e);
        }
    }

    /**
     * Create a parameter assertion specification for {@link
     * #hasToolCallWithParameterSatisfying(String, ParameterAssertion...)}.
     *
     * @param parameterName parameter key inside {@code arguments}
     * @param parameterAssertions assertions against the parameter value
     * @return parameter assertion specification
     */
    @SafeVarargs
    public static ParameterAssertion parameterSatisfying(
            String parameterName, Consumer<JsonNode>... parameterAssertions) {
        return new ParameterAssertion(parameterName, parameterAssertions);
    }

    /**
     * Assert the number of extracted tool calls.
     *
     * @param expected expected number of tool calls
     * @return this assertion instance
     */
    public ToolCallAssert hasToolCallCount(int expected) {
        isNotNull();
        assertThat(toolCalls)
                .withFailMessage(
                        "Expected %s tool call(s) but found %s.%nExtracted tool calls:%n%s",
                        expected, toolCalls.size(), toPrettyJson(toolCalls))
                .hasSize(expected);
        return this;
    }

    /**
     * Assert there are no extracted tool calls.
     *
     * @return this assertion instance
     */
    public ToolCallAssert hasNoToolCalls() {
        return hasToolCallCount(0);
    }

    /**
     * Assert that at least one extracted tool call has the given name.
     *
     * @param expectedName expected tool name
     * @return this assertion instance
     */
    public ToolCallAssert containsToolCallNamed(String expectedName) {
        isNotNull();
        assertThat(toolCallNames())
                .withFailMessage(
                        "Expected a tool call named '%s' but found names: %s",
                        expectedName, toolCallNames())
                .contains(expectedName);
        return this;
    }

    /**
     * Assert that all extracted tool calls have the given name.
     *
     * @param expectedName expected tool name
     * @return this assertion instance
     */
    public ToolCallAssert containsOnlyToolCallsNamed(String expectedName) {
        isNotNull();
        assertThat(toolCallNames())
                .withFailMessage(
                        "Expected all tool calls to be named '%s' but found names: %s",
                        expectedName, toolCallNames())
                .allMatch(expectedName::equals);
        return this;
    }

    /**
     * Assert that the tool call at a specific index has the expected name.
     *
     * @param index zero-based index in extracted tool calls
     * @param expectedName expected tool name
     * @return this assertion instance
     */
    public ToolCallAssert hasToolCallAtIndex(int index, String expectedName) {
        JsonNode toolCall = toolCallAt(index);
        assertThat(toolCall.path("name").asText())
                .withFailMessage(
                        "Expected tool call at index %s to be '%s' but was '%s'.",
                        index, expectedName, toolCall.path("name").asText())
                .isEqualTo(expectedName);
        return this;
    }

    /**
     * Assert that a specific argument of the tool call at index matches the expected value.
     *
     * @param toolCallIndex zero-based index in extracted tool calls
     * @param argumentName argument key inside {@code arguments}
     * @param expectedValue expected value (compared as JsonNode)
     * @return this assertion instance
     */
    public ToolCallAssert hasToolCallArgument(
            int toolCallIndex, String argumentName, Object expectedValue) {
        JsonNode toolCall = toolCallAt(toolCallIndex);
        JsonNode argumentsNode = toolCall.path("arguments");
        assertThat(argumentsNode.isObject())
                .withFailMessage(
                        "Expected tool call at index %s to contain an 'arguments' object but got:%n%s",
                        toolCallIndex, toPrettyJson(toolCall))
                .isTrue();
        JsonNode actualArgument = argumentsNode.get(argumentName);
        assertThat(actualArgument)
                .withFailMessage(
                        "Expected argument '%s' in tool call at index %s but it was missing.%nActual arguments:%n%s",
                        argumentName, toolCallIndex, toPrettyJson(argumentsNode))
                .isNotNull();
        JsonNode expectedNode = OBJECT_MAPPER.valueToTree(expectedValue);
        assertThat(actualArgument)
                .withFailMessage(
                        "Expected argument '%s' in tool call at index %s to be:%n%s%nbut was:%n%s",
                        argumentName,
                        toolCallIndex,
                        toPrettyJson(expectedNode),
                        toPrettyJson(actualArgument))
                .isEqualTo(expectedNode);
        return this;
    }

    /**
     * Assert full argument object equality for the tool call at index.
     *
     * @param toolCallIndex zero-based index in extracted tool calls
     * @param expectedArguments expected argument object
     * @return this assertion instance
     */
    public ToolCallAssert hasToolCallArguments(int toolCallIndex, JsonNode expectedArguments) {
        JsonNode toolCall = toolCallAt(toolCallIndex);
        JsonNode actualArguments = toolCall.path("arguments");
        assertThat(actualArguments)
                .withFailMessage(
                        "Expected tool call arguments at index %s to be:%n%s%nbut were:%n%s",
                        toolCallIndex,
                        toPrettyJson(expectedArguments),
                        toPrettyJson(actualArguments))
                .isEqualTo(expectedArguments);
        return this;
    }

    /**
     * Assert that at least one tool call with the given name exists and the given parameter matches
     * the provided matcher.
     *
     * @param toolName expected tool name
     * @param parameterName parameter key inside {@code arguments}
     * @param parameterMatcher matcher applied to the parameter value
     * @return this assertion instance
     */
    public ToolCallAssert hasToolCallWithParameterMatching(
            String toolName, String parameterName, Predicate<JsonNode> parameterMatcher) {
        isNotNull();
        assertThat(parameterMatcher)
                .withFailMessage("Expected parameter matcher to be non-null.")
                .isNotNull();

        List<JsonNode> matchingToolCalls = toolCallsByName(toolName);
        assertThat(matchingToolCalls)
                .withFailMessage(
                        "Expected at least one tool call named '%s' but found names: %s",
                        toolName, toolCallNames())
                .isNotEmpty();

        List<JsonNode> matchingParameters =
                matchingToolCalls.stream()
                        .map(toolCall -> toolCall.path("arguments").get(parameterName))
                        .filter(node -> node != null)
                        .filter(parameterMatcher::test)
                        .toList();

        assertThat(matchingParameters)
                .withFailMessage(
                        "Expected a '%s' tool call with parameter '%s' matching the predicate but none matched.%nTool calls with that name:%n%s",
                        toolName, parameterName, toPrettyJson(matchingToolCalls))
                .isNotEmpty();
        return this;
    }

    /**
     * Convenience overload for asserting a single parameter.
     *
     * @param toolName expected tool name
     * @param parameterName parameter key inside {@code arguments}
     * @param parameterAssertions assertions for the given parameter
     * @return this assertion instance
     */
    @SafeVarargs
    public final ToolCallAssert hasToolCallWithParameterSatisfying(
            String toolName, String parameterName, Consumer<JsonNode>... parameterAssertions) {
        return hasToolCallWithParameterSatisfying(
                toolName, parameterSatisfying(parameterName, parameterAssertions));
    }

    /**
     * Assert that at least one tool call with the given name exists and the given parameter
     * assertions all pass for the same tool call.
     *
     * @param toolName expected tool name
     * @param parameterAssertions parameter assertion specs; all must pass
     * @return this assertion instance
     */
    public final ToolCallAssert hasToolCallWithParameterSatisfying(
            String toolName, ParameterAssertion... parameterAssertions) {
        isNotNull();
        assertThat(parameterAssertions)
                .withFailMessage("Expected parameter assertion specs to be non-null and non-empty.")
                .isNotNull();
        assertThat(parameterAssertions)
                .withFailMessage("Expected at least one parameter assertion spec.")
                .isNotEmpty();
        assertThat(parameterAssertions)
                .withFailMessage("Expected all parameter assertion specs to be non-null.")
                .doesNotContainNull();

        for (ParameterAssertion parameterAssertion : parameterAssertions) {
            assertThat(parameterAssertion.parameterName())
                    .withFailMessage("Expected parameter name to be non-null and non-blank.")
                    .isNotBlank();
            assertThat(parameterAssertion.parameterAssertions())
                    .withFailMessage(
                            "Expected assertions for parameter '%s' to be non-null and non-empty.",
                            parameterAssertion.parameterName())
                    .isNotNull();
            assertThat(parameterAssertion.parameterAssertions())
                    .withFailMessage(
                            "Expected at least one assertion for parameter '%s'.",
                            parameterAssertion.parameterName())
                    .isNotEmpty();
            assertThat(parameterAssertion.parameterAssertions())
                    .withFailMessage(
                            "Expected all assertions for parameter '%s' to be non-null.",
                            parameterAssertion.parameterName())
                    .doesNotContainNull();
        }

        List<JsonNode> matchingToolCalls = toolCallsByName(toolName);
        assertThat(matchingToolCalls)
                .withFailMessage(
                        "Expected at least one tool call named '%s' but found names: %s",
                        toolName, toolCallNames())
                .isNotEmpty();

        AssertionError lastError = null;
        for (JsonNode matchingToolCall : matchingToolCalls) {
            try {
                JsonNode arguments = matchingToolCall.path("arguments");
                assertThat(arguments.isObject())
                        .withFailMessage(
                                "Expected tool call '%s' to contain an 'arguments' object but got:%n%s",
                                toolName, toPrettyJson(matchingToolCall))
                        .isTrue();

                for (ParameterAssertion parameterAssertion : parameterAssertions) {
                    JsonNode parameterValue = arguments.get(parameterAssertion.parameterName());
                    assertThat(parameterValue)
                            .withFailMessage(
                                    "Expected parameter '%s' in tool call named '%s' but it was missing.%nTool call:%n%s",
                                    parameterAssertion.parameterName(),
                                    toolName,
                                    toPrettyJson(matchingToolCall))
                            .isNotNull();

                    for (Consumer<JsonNode> assertion : parameterAssertion.parameterAssertions()) {
                        assertion.accept(parameterValue);
                    }
                }
                return this;
            } catch (AssertionError e) {
                lastError = e;
            }
        }

        List<String> expectedParameterNames =
                Arrays.stream(parameterAssertions).map(ParameterAssertion::parameterName).toList();
        throw new AssertionError(
                String.format(
                        "Parameter assertions failed for all '%s' tool calls.%nExpected parameters:%s%nTool calls:%n%s",
                        toolName, expectedParameterNames, toPrettyJson(matchingToolCalls)),
                lastError);
    }

    /** Parameter assertion specification used by {@link ToolCallAssert}. */
    public static final class ParameterAssertion {
        private final String parameterName;
        private final Consumer<JsonNode>[] parameterAssertions;

        @SafeVarargs
        private ParameterAssertion(
                String parameterName, Consumer<JsonNode>... parameterAssertions) {
            this.parameterName = parameterName;
            this.parameterAssertions = parameterAssertions;
        }

        private String parameterName() {
            return parameterName;
        }

        private Consumer<JsonNode>[] parameterAssertions() {
            return parameterAssertions;
        }
    }

    private JsonNode toolCallAt(int index) {
        isNotNull();
        assertThat(index)
                .withFailMessage("Expected index to be >= 0 but was %s.", index)
                .isGreaterThanOrEqualTo(0);
        assertThat(toolCalls)
                .withFailMessage(
                        "Expected tool call index %s to exist, but only %s tool call(s) were found.",
                        index, toolCalls.size())
                .hasSizeGreaterThan(index);
        return toolCalls.get(index);
    }

    private List<String> toolCallNames() {
        return toolCalls.stream().map(node -> node.path("name").asText()).toList();
    }

    private List<JsonNode> toolCallsByName(String toolName) {
        return toolCalls.stream()
                .filter(node -> toolName.equals(node.path("name").asText()))
                .toList();
    }

    private static List<JsonNode> extractToolCalls(JsonNode payload) {
        List<JsonNode> extractedToolCalls = new ArrayList<>();
        if (payload == null || payload.isNull()) {
            return extractedToolCalls;
        }

        // Single tool call object: { "name": "...", "arguments": {...} }
        if (isToolCallNode(payload)) {
            extractedToolCalls.add(payload);
            return extractedToolCalls;
        }

        // Root-level toolCalls array.
        JsonNode rootToolCalls = payload.get("toolCalls");
        if (rootToolCalls != null && rootToolCalls.isArray()) {
            rootToolCalls.forEach(extractedToolCalls::add);
        }

        // Fixture/main shape: conversation.messages[*].toolCalls[*].
        JsonNode messages = payload.path("conversation").path("messages");
        if (messages.isArray()) {
            messages.forEach(message -> addToolCallsFromMessage(message, extractedToolCalls));
        }

        // Generic array support (messages array or tool-call array).
        if (payload.isArray()) {
            payload.forEach(
                    node -> {
                        if (isToolCallNode(node)) {
                            extractedToolCalls.add(node);
                        } else {
                            addToolCallsFromMessage(node, extractedToolCalls);
                        }
                    });
        }

        return extractedToolCalls;
    }

    private static void addToolCallsFromMessage(JsonNode message, List<JsonNode> sink) {
        JsonNode toolCallsNode = message.get("toolCalls");
        if (toolCallsNode != null && toolCallsNode.isArray()) {
            StreamSupport.stream(toolCallsNode.spliterator(), false).forEach(sink::add);
        }
    }

    private static boolean isToolCallNode(JsonNode node) {
        return node != null && node.isObject() && node.has("name") && node.has("arguments");
    }

    private static String toPrettyJson(Object value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
