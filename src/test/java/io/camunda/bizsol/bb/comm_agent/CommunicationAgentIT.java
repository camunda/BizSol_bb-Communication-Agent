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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ResourceUtils;
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
    private static final String OPEN_AI_API_BASEURL = "https://api.openai.com";
    private static final String OPEN_AI_API_ENDPOINT = OPEN_AI_API_BASEURL + "/v1/";
    private static final String OPEN_AI_API_KEY = "<secret>";
    private static final String LLM_MODEL = "gpt-5-mini-2025-08-07";
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

    // ------------- Strings ---------------------
    private static final String EXPECTED_CUSTOMER_DESIRE =
            "Customer needs assistance and would like support with their requirements.";
    private static final String EXPECTED_MESSAGE_TEXT =
            "I've connected you with our specialist team who will assist with your needs";
    private static final String CUSTOMER_MESSAGE_SUBJECT = "Invoicing issue";
    private static final String INITIAL_CUSTOMER_MESSAGE = "Need help with my invoice.";
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
                        ResourceUtils.getFile(COMMUNICATION_AGENT_RECEIVE_FILE),
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
                                .formatted(EXPECTED_MESSAGE_TEXT)));

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
                            String text = communicationContent.get("text").asText();
                            assertThat(text).isNotEmpty();
                            log.info("CommunicationContent is: " + text);
                            // assertCustomerMessageText(text);
                        });
    }

    @Test
    @DisplayName(
            "A new message that correlates to an open conversation leats to a notification of the business agent")
    void newCommunicationShouldNotifyBusinessAgent() {
        // given
        publishCustomerCommunicationReceived(SUPPORT_CASE, EMAIL_ADDRESS);
        assertCommunicationAgentIsWaiting();

        // when
        publishCustomerCommunicationReceived(
                SUPPORT_CASE.withRequest(FOLLOW_UP_CUSTOMER_MESSAGE), EMAIL_ADDRESS);

        // then
        assertCommunicationAgentIsWaiting();
        assertBusinessAgentWasNotifiedAboutNewCommunication(
                INITIAL_CUSTOMER_MESSAGE, EXPECTED_FOLLOW_UP_CUSTOMER_INTENT);
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
            String expectedOriginalMessage, String expectedCustomerIntent) {
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
                        correlationKey -> assertThat(correlationKey).isEqualTo(EMAIL_ADDRESS))
                .hasLocalVariableSatisfies(
                        NOTIFY_BUSINESS_AGENT_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            JsonNode communicationResult = variables.get("communicationResult");
                            assertThat(communicationResult).isNotNull();
                            log.info(
                                    "communicationResult: {}",
                                    communicationResult.toPrettyString());
                            assertThat(communicationResult.get("status").asText())
                                    .isEqualTo("success");
                            //                            assertCustomerMessageWasForwarded(
                            //
                            // communicationResult.get("originalText").asText(),
                            //                                    expectedOriginalMessage);
                            assertCustomerIntentIsRelevant(
                                    communicationResult.get("text").asText(),
                                    expectedCustomerIntent);
                        });
    }

    private void assertCustomerDesireIsRelevant(JsonNode customerDesire) {
        String actualCustomerDesire = customerDesire.asText();
        assertThat(actualCustomerDesire).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(EXPECTED_CUSTOMER_DESIRE, List.of(), actualCustomerDesire);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerMessageText(String actualCustomerDesire) {
        assertThat(actualCustomerDesire).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(EXPECTED_MESSAGE_TEXT, List.of(), actualCustomerDesire);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerMessageWasForwarded(
            String actualCustomerMessage, String expectedCustomerMessage) {
        assertThat(actualCustomerMessage).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(expectedCustomerMessage, List.of(), actualCustomerMessage);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    private void assertCustomerIntentIsRelevant(
            String actualCustomerIntent, String expectedCustomerIntent) {
        assertThat(actualCustomerIntent).isNotBlank();

        EvaluationRequest request =
                new EvaluationRequest(expectedCustomerIntent, List.of(), actualCustomerIntent);
        EvaluationResponse response = evaluator.evaluate(request);
        assertThat(response.isPass()).describedAs(response.getFeedback()).isTrue();
    }

    @TestConfiguration
    static class TestEvaluatorConfiguration {

        @Bean
        Evaluator evaluator(ChatModel chatModel) {
            return new RelevancyEvaluator(ChatClient.builder(chatModel));
        }
    }
}
