package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.bizsol.bb.comm_agent.util.ToolCallAssert.assertThatToolCalls;
import static io.camunda.bizsol.bb.comm_agent.util.ToolCallAssert.parameterSatisfying;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.setAssertionTimeout;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ResourceUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;

@Slf4j
@SpringBootTest(
        properties = {
            "camunda.client.worker.defaults.enabled=false",
            "camunda.connector.secretprovider.environment.prefix=''",
            "camunda.process-test.coverage.reportDirectory: target/coverage-report-integration"
        })
@CamundaSpringProcessTest
@Testcontainers
public class CommunicationAgentIT {

    // private static final String LLM_MODEL = "qwen2.5:1.5b";
    private static final String LLM_MODEL = "qwen3.5:2b";
    private static final int SERVER_PORT = 8080;
    private static final int CONTEXT_WINDOW = 4096;
    private static final String COMMUNICATION_AGENT_RECEIVE_FILE =
            "camunda-artifacts/communication-agent.bpmn";
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(120);

    // --------------Process and Element Ids ----------------
    private static final String PROCESS_DEFINITION_ID = "customer-communication-agent";
    private static final String COMMUNICATION_AGENT_SUB_PROCESS = "SubProcess_CommunicationAgent";
    private static final String NOTIFY_BUSINESS_AGENT_ELEMENT_ID = "Tool_NotifyBusinessAgent";
    private static final String MESSAGE_CUSTOMER_TOOL_ELEMENT_ID = "Tool_MessageCustomer";
    private static final String START_BUSINESS_AGENT_TOOL_ELEMENT_ID = "Tool_StartBusinessAgent";
    private static final String WAIT_TOOL_ELEMENT_ID = "Tool_WAIT";

    // -------------- Messages -------------------
    private static final String START_MESSAGE_NAME = "CustomerCommunicationReceived";
    private static final String COMMUNICATION_REQUIRED_MESSAGE_NAME =
            "CustomerCommunicationRequired";
    private static final String NOTIFY_BUSINESS_AGENT_MESSAGE_NAME = "businessAgentNotify";

    // ------------- Variables ------------------
    private static final String AGENT_CONTEXT_VARIABLE = "agentContext";
    private static final String CUSTOMER_DESIRE_PARAMETER = "customerDesireToPassToTheAgent";
    private static final String CUSTOMER_MESSAGE_IN_FULL_PARAMETER = "customerMessageInFull";
    private static final String CUSTOMER_INTENT_PARAMETER = "customerIntent";

    // ------------- Strings ---------------------
    private static final String EXPECTED_CUSTOMER_DESIRE =
            "Customer needs assistance and would like support with their requirements.";
    private static final String EXPECTED_MESSAGE_TEXT =
            "I've connected you with our specialist team who will assist with your needs";
    private static final String FOLLOW_UP_CUSTOMER_MESSAGE =
            "I was charged twice for invoice INV-123 and need help fixing it.";
    private static final String EXPECTED_FOLLOW_UP_CUSTOMER_INTENT =
            "Customer needs support with a duplicate charge on invoice INV-123.";
    private static final String ACKNOWLEDGEMENT_CUSTOMER_MESSAGE =
            "Thanks, that makes sense. Please continue handling the case.";
    private static final String EXPECTED_ACKNOWLEDGEMENT_CUSTOMER_INTENT =
            "Customer acknowledges the update and asks the team to continue with the case.";

    // ------------ Test Fixtures ----------------

    private static final String EMAIL_ADDRESS = "customer@camunda.com";
    private static final SupportCase SUPPORT_CASE =
            SupportCase.builder()
                    .subject("Email case")
                    .request("Need help")
                    .receivedDateTime(LocalDateTime.of(2026, 2, 20, 9, 30))
                    .attachments(Collections.emptyList())
                    .communicationContext(
                            EmailCommunicationContext.builder()
                                    .conversationId("conv-email-001")
                                    .emailAddress(EMAIL_ADDRESS)
                                    .build())
                    .build();

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;
    @Autowired private Evaluator evaluator;

    @Container
    private static final OllamaContainer ollama =
            new OllamaContainer("ollama/ollama")
                    .withFileSystemBind(
                            System.getProperty("user.home") + "/.cache/ollama", "/root/.ollama");

    static String getOllamaIp() {
        return ollama.getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress();
    }

    @BeforeEach
    void deployProcess() throws FileNotFoundException {
        BpmnModelInstance communicationAgentModel =
                BpmnFile.replace(
                        ResourceUtils.getFile(COMMUNICATION_AGENT_RECEIVE_FILE),
                        // Add none start event
                        replace(
                                """
                                        <zeebe:input source="" target="provider.openaiCompatible.endpoint" />
                                        """
                                        .stripLeading(),
                                """
                                        <zeebe:input source="{{secrets.LLM_PROVIDER_API_ENDPOINT}}" target="provider.openaiCompatible.endpoint" />
                                        """
                                        .stripLeading()),
                        replace(
                                """
                                        <zeebe:input source="" target="provider.openaiCompatible.model.model" />
                                        """
                                        .stripLeading(),
                                """
                                        <zeebe:input source="{{secrets.LLM_MODEL}}" target="provider.openaiCompatible.model.model" />
                                        """
                                        .stripLeading()));
        client.newDeployResourceCommand()
                .addProcessModel(communicationAgentModel, COMMUNICATION_AGENT_RECEIVE_FILE)
                .send()
                .join();

        // Mock outbound sub-processs
        processTestContext.mockChildProcess(
                "message-sender",
                Map.of(
                        "toolCallResult",
                        """
                                {"status": "success"}
                                """));

        // Increase timeout to wait for llm
        setAssertionTimeout(ASSERTION_TIMEOUT);
    }

    @Test
    @DisplayName("A new incoming message should be passed to the business agent")
    void incomingMessageShouldBePassedToBusinessAgent() {
        // when
        publishCustomerCommunicationReceived(SUPPORT_CASE, EMAIL_ADDRESS);

        // then
        assertCommunicationAgentIsWaiting();
        assertBusinessAgentToolCallContainsRelevantCustomerDesire();
    }

    @Test
    @DisplayName("When a business agent requires communication, the right tool is triggered")
    void businessProcessRequiresCommunication() throws InterruptedException {
        // when
        publishCustomerCommunicationReceived(SUPPORT_CASE, EMAIL_ADDRESS);
        assertCommunicationAgentIsWaiting();

        // then
        publishCommunicationRequiredMessage(
                Map.of(
                        "communicationContent",
                        """
                        {
                            "subject":"RE: Email case",
                            "text":"Hi User!"
                        }
                        """,
                        "communicationContext",
                        """
                        {
                            "channel":"email",
                            "emailAddress":"customer@camunda.com",
                            "conversationId":"<968036FE-0C58-49E8-920A-A1C02F44D85E@holisticon.de>"
                        }
                        """
                                .stripIndent()));

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isActive()
                .hasActiveElements(COMMUNICATION_AGENT_SUB_PROCESS)
                .hasCompletedElements(MESSAGE_CUSTOMER_TOOL_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        MESSAGE_CUSTOMER_TOOL_ELEMENT_ID,
                        "communicationContent",
                        JsonNode.class,
                        communicationContent -> {
                            assertThat(communicationContent.asText()).isNotNull();
                            assertCustomerMessageText(communicationContent.get("text"));
                        });
    }

    @Test
    @DisplayName(
            "A new message that correlates to an open conversation leats to a notification of the business agent")
    void newCommunicationShouldNotifyBusinessAgent() {
        // when
        publishCustomerCommunicationReceived(SUPPORT_CASE, EMAIL_ADDRESS);
        assertCommunicationAgentIsWaiting();

        publishCustomerCommunicationReceived(
                SUPPORT_CASE.withSubject(FOLLOW_UP_CUSTOMER_MESSAGE), EMAIL_ADDRESS);

        // then
        assertCommunicationAgentIsWaiting();
        assertBusinessAgentWasNotifiedAboutNewCommunication(
                FOLLOW_UP_CUSTOMER_MESSAGE, EXPECTED_FOLLOW_UP_CUSTOMER_INTENT);
    }

    private void publishCommunicationRequiredMessage(Map<String, Object> communicationContent) {
        client.newPublishMessageCommand()
                .messageName(CommunicationAgentIT.COMMUNICATION_REQUIRED_MESSAGE_NAME)
                .correlationKey(CommunicationAgentIT.EMAIL_ADDRESS)
                .variables(communicationContent)
                .send()
                .join();
    }

    private void publishCustomerCommunicationReceived(
            SupportCase supportCase, String correlationKey) {
        client.newPublishMessageCommand()
                .messageName(START_MESSAGE_NAME)
                .correlationKey(correlationKey)
                .variables(Map.of("supportCase", supportCase))
                .send()
                .join();
    }

    private void assertCommunicationAgentIsWaiting() {
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isActive()
                .hasActiveElements(COMMUNICATION_AGENT_SUB_PROCESS)
                .hasActiveElements(WAIT_TOOL_ELEMENT_ID);
    }

    private void assertBusinessAgentToolCallContainsRelevantCustomerDesire() {
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .hasLocalVariableSatisfies(
                        COMMUNICATION_AGENT_SUB_PROCESS,
                        AGENT_CONTEXT_VARIABLE,
                        JsonNode.class,
                        agentContext ->
                                assertThatToolCalls(agentContext)
                                        .containsToolCallNamed(START_BUSINESS_AGENT_TOOL_ELEMENT_ID)
                                        .hasToolCallWithParameterSatisfying(
                                                START_BUSINESS_AGENT_TOOL_ELEMENT_ID,
                                                parameterSatisfying(
                                                        CUSTOMER_DESIRE_PARAMETER,
                                                        this::assertCustomerDesireIsRelevant)));
    }

    private void assertBusinessAgentWasNotifiedAboutNewCommunication(
            String expectedCustomerMessage, String expectedCustomerIntent) {
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isActive()
                .hasCompletedElements(NOTIFY_BUSINESS_AGENT_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "messageName",
                        JsonNode.class,
                        messageName ->
                                assertThat(messageName.asText())
                                        .isEqualTo(NOTIFY_BUSINESS_AGENT_MESSAGE_NAME))
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "correlationKey",
                        JsonNode.class,
                        correlationKey ->
                                assertThat(correlationKey.asText()).isEqualTo(EMAIL_ADDRESS))
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            JsonNode communicationResult = variables.get("communicationResult");
                            assertThat(communicationResult).isNotNull();
                            assertThat(communicationResult.get("status").asText())
                                    .isEqualTo("success");
                            assertCustomerMessageWasForwarded(
                                    communicationResult.get("originalText"),
                                    expectedCustomerMessage);
                            assertCustomerIntentIsRelevant(
                                    communicationResult.get("text"), expectedCustomerIntent);
                        })
                .hasLocalVariableSatisfies(
                        COMMUNICATION_AGENT_SUB_PROCESS,
                        AGENT_CONTEXT_VARIABLE,
                        JsonNode.class,
                        agentContext ->
                                assertThatToolCalls(agentContext)
                                        .containsToolCallNamed(NOTIFY_BUSINESS_AGENT_ELEMENT_ID)
                                        .hasToolCallWithParameterSatisfying(
                                                NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                                                parameterSatisfying(
                                                        CUSTOMER_MESSAGE_IN_FULL_PARAMETER,
                                                        parameter ->
                                                                assertCustomerMessageWasForwarded(
                                                                        parameter,
                                                                        expectedCustomerMessage)),
                                                parameterSatisfying(
                                                        CUSTOMER_INTENT_PARAMETER,
                                                        parameter ->
                                                                assertCustomerIntentIsRelevant(
                                                                        parameter,
                                                                        expectedCustomerIntent))));
    }

    private void assertCustomerDesireIsRelevant(JsonNode customerDesire) {
        String actualCustomerDesire = customerDesire.asText();
        assertThat(actualCustomerDesire).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(EXPECTED_CUSTOMER_DESIRE, List.of(), actualCustomerDesire);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerMessageText(JsonNode messageText) {
        String actualCustomerDesire = messageText.asText();
        assertThat(actualCustomerDesire).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(EXPECTED_MESSAGE_TEXT, List.of(), actualCustomerDesire);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerMessageWasForwarded(
            JsonNode customerMessageInFull, String expectedCustomerMessage) {
        String actualCustomerMessage = customerMessageInFull.asText();
        assertThat(actualCustomerMessage).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(expectedCustomerMessage, List.of(), actualCustomerMessage);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerIntentIsRelevant(
            JsonNode customerIntent, String expectedCustomerIntent) {
        String actualCustomerIntent = customerIntent.asText();
        assertThat(actualCustomerIntent).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(expectedCustomerIntent, List.of(), actualCustomerIntent);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry)
            throws IOException, InterruptedException {
        ollama.start();
        ollama.execInContainer("ollama", "run", LLM_MODEL);
        String llmEndpoint = String.format("http://%s:%d/v1/", getOllamaIp(), 11434);
        // Camunda connector secrets
        registry.add(
                "camunda.process-test.connectors-secrets.LLM_PROVIDER_API_ENDPOINT",
                () -> llmEndpoint);
        registry.add("camunda.process-test.connectors-secrets.LLM_MODEL", () -> LLM_MODEL);
        // Spring AI settings for evaluations
        registry.add("spring.ai.openai.base-url", () -> llmEndpoint);
        registry.add("spring.ai.openai.chat.options.model", () -> LLM_MODEL);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfiguration {

        @Bean
        Evaluator evaluator(ChatModel chatModel) {
            return new RelevancyEvaluator(ChatClient.builder(chatModel));
        }
    }
}
