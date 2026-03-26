package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.setAssertionTimeout;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.bizsol.bb.comm_agent.util.SemanticMatchingEvaluator;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.CollectionAssert;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    private static final String COMMUNICATION_AGENT_RECEIVE_FILE =
            "camunda-artifacts/communication-agent.bpmn";
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(45);
    private static final int LOG_PREVIEW_LENGTH = 160;

    // --------------Process and Element Ids ----------------
    private static final String PROCESS_DEFINITION_ID = "customer-communication-agent";
    private static final String COMMUNICATION_AGENT_SUB_PROCESS = "SubProcess_CommunicationAgent";
    private static final String NOTIFY_BUSINESS_AGENT_ELEMENT_ID = "Tool_NotifyBusinessAgent";
    private static final String MESSAGE_CUSTOMER_TOOL_ELEMENT_ID = "Tool_MessageCustomer";
    private static final String START_BUSINESS_AGENT_TOOL_ELEMENT_ID = "Tool_StartBusinessAgent";
    private static final String WAIT_TOOL_ELEMENT_ID = "Tool_WAIT";
    private static final String ERROR_END_EVENT_ID = "Event_1nryd5n";
    private static final String ERROR_VARIABLE = "error";

    // -------------- Messages -------------------
    private static final String START_MESSAGE_NAME = "CustomerCommunicationReceived";
    private static final String COMMUNICATION_REQUIRED_MESSAGE_NAME =
            "CustomerCommunicationRequired";
    private static final String START_BUSINESS_AGENT_MESSAGE_NAME = "businessAgentStart";
    private static final String NOTIFY_BUSINESS_AGENT_MESSAGE_NAME = "businessAgentNotify";

    // ------------- Variables ------------------
    private static final String CUSTOMER_REQUEST_PARAMETER = "request";

    // ------------- Strings ---------------------
    private static final String EXPECTED_CUSTOMER_DESIRE =
            "Customer needs assistance with his invoice. Request more details.";
    private static final String BUSINESS_AGENT_MESSAGE_TO_CUSTOMER =
            "I've connected you with our specialist team who will assist with your needs";
    private static final String CUSTOMER_MESSAGE_SUBJECT = "Invoicing issue";
    private static final String INITIAL_CUSTOMER_MESSAGE = "Need help with my invoice.";
    private static final String FOLLOW_UP_CUSTOMER_MESSAGE =
            "I was charged twice for invoice INV-123 and need help fixing it.";
    private static final String EXPECTED_FOLLOW_UP_CUSTOMER_INTENT =
            "Duplicate charge on invoice INV-123.";

    // ------------ Test Fixtures ----------------

    private static final String EMAIL_ADDRESS = "customer@camunda.com";
    private static final SupportCase SUPPORT_CASE =
            SupportCase.builder()
                    .subject(CUSTOMER_MESSAGE_SUBJECT)
                    .request(INITIAL_CUSTOMER_MESSAGE)
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

    @BeforeEach
    void deployProcess() throws FileNotFoundException {
        BpmnModelInstance communicationAgentModel =
                BpmnFile.replace(
                        COMMUNICATION_AGENT_RECEIVE_FILE,
                        // Add none start event
                        replace(
                                """
                                        <zeebe:input source="" target="provider.openaiCompatible.endpoint" />
                                        """
                                        .stripLeading(),
                                """
                                        <zeebe:input source="{{secrets.LLM_API_ENDPOINT}}" target="provider.openaiCompatible.endpoint" />
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
                                        .stripLeading()),
                        replace(
                                """
                                        <zeebe:input source="" target="provider.openaiCompatible.authentication.apiKey" />"""
                                        .stripLeading(),
                                """
                                        <zeebe:input source="{{secrets.LLM_API_KEY}}" target="provider.openaiCompatible.authentication.apiKey" />"""
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
        // given
        final String correlationKey = UUID.randomUUID().toString();

        // when
        publishCustomerCommunicationReceived(SUPPORT_CASE.withCorrelationKey(correlationKey));

        // then
        assertCommunicationAgentIsWaiting();
        assertBusinessAgentToolCallContainsRelevantCustomerDesire(correlationKey);
    }

    @Test
    @DisplayName("When a business agent requires communication, the outbound tool is triggered")
    void businessProcessRequiresCommunication() {
        // given
        final String correlationKey = UUID.randomUUID().toString();

        // when
        publishCustomerCommunicationReceived(
                SUPPORT_CASE.withCorrelationKey(correlationKey).withCorrelationKey(correlationKey));
        assertCommunicationAgentIsWaiting();

        // then
        publishCommunicationRequiredMessage(
                Map.of(
                        "communicationContent",
                        """
                        {
                            "subject":"RE: Email case",
                            "text":"%s"
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
                                .stripIndent()
                                .formatted(BUSINESS_AGENT_MESSAGE_TO_CUSTOMER)),
                correlationKey);

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
                            assertRelevanceMatches(
                                    communicationContent.get("text").asText(),
                                    BUSINESS_AGENT_MESSAGE_TO_CUSTOMER);
                        });
    }

    @Test
    @DisplayName(
            "A new message that correlates to an open conversation leats to a notification of the business agent")
    void newCommunicationShouldNotifyBusinessAgent() {
        // given
        final String correlationKey = UUID.randomUUID().toString();
        publishCustomerCommunicationReceived(SUPPORT_CASE.withCorrelationKey(correlationKey));
        assertCommunicationAgentIsWaiting();

        // when
        publishCustomerCommunicationReceived(
                SUPPORT_CASE
                        .withRequest(FOLLOW_UP_CUSTOMER_MESSAGE)
                        .withCorrelationKey(correlationKey));

        // then
        assertCommunicationAgentIsWaiting();
        assertBusinessAgentWasNotifiedAboutNewCommunication(
                FOLLOW_UP_CUSTOMER_MESSAGE, EXPECTED_FOLLOW_UP_CUSTOMER_INTENT, correlationKey);
    }

    @Test
    @DisplayName(
            "A new message that does not correlates to an open conversation creates a new process instance")
    void unrelatedCommunicationShouldCreateNewProcess() {
        // given
        final String correlationKey = UUID.randomUUID().toString();
        publishCustomerCommunicationReceived(SUPPORT_CASE.withCorrelationKey(correlationKey));
        assertCommunicationAgentIsWaiting();

        // when
        publishCustomerCommunicationReceived(
                SUPPORT_CASE
                        .withRequest(FOLLOW_UP_CUSTOMER_MESSAGE)
                        .withCorrelationKey("UNRELATED-CORRELATION_KEY"));

        // then
        Awaitility.await()
                .untilAsserted(
                        () -> {
                            SearchResponse<ProcessInstance> results =
                                    client.newProcessInstanceSearchRequest()
                                            .filter(
                                                    f ->
                                                            f.processDefinitionId(
                                                                    PROCESS_DEFINITION_ID))
                                            .send()
                                            .join();
                            CollectionAssert.assertThatCollection(results.items()).hasSize(2);
                        });
    }

    private List<ElementInstance> getElementInstances() {
        return client.newElementInstanceSearchRequest()
                .filter(e -> e.processDefinitionId(PROCESS_DEFINITION_ID))
                .send()
                .join()
                .items();
    }

    @Test
    @DisplayName("An exception should be caught and saved to error variable")
    void emptyCustomerMessageShouldRaiseDocumentedBpmnError() {
        // given
        final String correlationKey = UUID.randomUUID().toString();

        // when
        publishCustomerCommunicationReceived(
                SUPPORT_CASE.withRequest(null).withCorrelationKey(correlationKey));

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements(ERROR_END_EVENT_ID)
                .hasVariableSatisfies(
                        ERROR_VARIABLE,
                        JsonNode.class,
                        error -> {
                            assertThat(error.get("type").asText())
                                    .isEqualTo(
                                            "io.camunda.connector.api.error.ConnectorInputException");
                            assertThat(error.get("message").asText()).isNotBlank();
                        });
    }

    private void publishCommunicationRequiredMessage(
            Map<String, Object> communicationContent, String correlationKey) {
        client.newPublishMessageCommand()
                .messageName(CommunicationAgentIT.COMMUNICATION_REQUIRED_MESSAGE_NAME)
                .correlationKey(correlationKey)
                .variables(communicationContent)
                .send()
                .join();
    }

    private void publishCustomerCommunicationReceived(SupportCase supportCase) {
        client.newPublishMessageCommand()
                .messageName(START_MESSAGE_NAME)
                .correlationKey(supportCase.correlationKey())
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

    private void assertBusinessAgentToolCallContainsRelevantCustomerDesire(
            String expectedCorrelationKey) {
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isActive()
                .hasCompletedElements(START_BUSINESS_AGENT_TOOL_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        START_BUSINESS_AGENT_TOOL_ELEMENT_ID,
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName)
                                        .isEqualTo(START_BUSINESS_AGENT_MESSAGE_NAME))
                .hasLocalVariableSatisfies(
                        START_BUSINESS_AGENT_TOOL_ELEMENT_ID,
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(expectedCorrelationKey))
                .hasLocalVariableSatisfies(
                        START_BUSINESS_AGENT_TOOL_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            assertRelevanceMatches(
                                    variables.get(CUSTOMER_REQUEST_PARAMETER).asText(),
                                    EXPECTED_CUSTOMER_DESIRE);
                        });
    }

    private void assertBusinessAgentWasNotifiedAboutNewCommunication(
            String expectedOriginalMessage,
            String expectedCustomerIntent,
            String expectedCorrelationKey) {
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isActive()
                .hasCompletedElements(NOTIFY_BUSINESS_AGENT_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName)
                                        .isEqualTo(NOTIFY_BUSINESS_AGENT_MESSAGE_NAME))
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(expectedCorrelationKey))
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            JsonNode communicationResult = variables.get("communicationResult");
                            assertThat(communicationResult).isNotNull();
                            log.debug(
                                    "communicationResult: {}",
                                    communicationResult.toPrettyString());
                            assertThat(communicationResult.get("status").asText())
                                    .isEqualTo("success");
                            assertThat(communicationResult.get("originalText").asText())
                                    .isEqualTo(expectedOriginalMessage);
                            assertRelevanceMatches(
                                    communicationResult.get("text").asText(),
                                    expectedCustomerIntent);
                        });
    }

    private void assertRelevanceMatches(
            String actualCustomerIntent, String expectedCustomerIntent) {
        assertThat(actualCustomerIntent).isNotBlank();

        log.debug(
                "Evaluating relevance. expected='{}', actual='{}'",
                previewForLog(expectedCustomerIntent),
                previewForLog(actualCustomerIntent));

        EvaluationRequest request =
                new EvaluationRequest(expectedCustomerIntent, actualCustomerIntent);
        EvaluationResponse response = evaluator.evaluate(request);

        if (response.isPass()) {
            log.info("Relevance evaluation passed.");
        } else {
            log.warn(
                    "Relevance evaluation failed. score={}, feedback='{}', expected='{}', actual='{}'",
                    response.getScore(),
                    previewForLog(response.getFeedback()),
                    previewForLog(expectedCustomerIntent),
                    previewForLog(actualCustomerIntent));
        }

        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private String previewForLog(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= LOG_PREVIEW_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, LOG_PREVIEW_LENGTH - 3) + "...";
    }

    @TestConfiguration
    static class TestEvaluatorConfiguration {
        @Bean
        Evaluator evaluator(ChatModel chatModel) {
            return new SemanticMatchingEvaluator(ChatClient.builder(chatModel), 0.7F);
        }
    }
}
