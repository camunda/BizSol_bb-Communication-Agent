package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.bizsol.bb.comm_agent.models.CustomCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.PhoneCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"camunda.client.worker.defaults.enabled=false"})
@CamundaSpringProcessTest
public class CaseMatchingTest {

    private static final String PROCESS_DEFINITION_ID = "case-matching";
    private static final String START_MESSAGE_NAME = "UnmatchedCommunicationReceived";
    private static final String CUSTOM_CONVERSATION_ID = "4711";
    public static final String EXPECTED_MESSAGE_NAME = "CustomerCommunicationReceived";

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;

    @BeforeEach
    void deployProcess() {
        client.newDeployResourceCommand()
                .addResourceFromClasspath("case-matching.bpmn")
                .send()
                .join();
    }

    @Test
    void shouldUseEmailAddressAsCorrelationKeyAndMessageName() {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Email case")
                        .request("Need help")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 20, 9, 30))
                        .attachments(Collections.emptyList())
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .conversationId("conv-email-001")
                                        .emailAddress("customer@camunda.com")
                                        .build())
                        .build();

        // when
        publishStartMessage(supportCase);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements("Event_SendCustomerCommunicationReceived")
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo("customer@camunda.com"))
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "messageName",
                        String.class,
                        messageName -> assertThat(messageName).isEqualTo(EXPECTED_MESSAGE_NAME));
    }

    @Test
    void shouldUsePhoneNumberAsCorrelationKeyAndMessageName() {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Phone case")
                        .request("Please call me back")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 21, 11, 15))
                        .attachments(Collections.emptyList())
                        .communicationContext(
                                PhoneCommunicationContext.builder()
                                        .conversationId("conv-phone-001")
                                        .phoneNumber("+1-202-555-0183")
                                        .build())
                        .build();

        // when
        publishStartMessage(supportCase);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements("Event_SendCustomerCommunicationReceived")
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "correlationKey",
                        String.class,
                        correlationKey -> assertThat(correlationKey).isEqualTo("+1-202-555-0183"))
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName).isEqualTo("CustomerCommunicationReceived"));
    }

    @Test
    void shouldUseConversationIdAsCorrelationKeyAndMessageName() {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Phone case")
                        .request("Please call me back")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 21, 11, 15))
                        .attachments(Collections.emptyList())
                        .communicationContext(new MyCommunicationContext(CUSTOM_CONVERSATION_ID))
                        .build();

        // when
        publishStartMessage(supportCase);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements("Event_SendCustomerCommunicationReceived")
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(CUSTOM_CONVERSATION_ID))
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName).isEqualTo("CustomerCommunicationReceived"));
    }

    private void publishStartMessage(SupportCase supportCase) {
        client.newPublishMessageCommand()
                .messageName(START_MESSAGE_NAME)
                .correlationKey(supportCase.communicationContext().conversationId())
                .variables(Map.of("supportCase", supportCase))
                .send()
                .join();
    }

    private record MyCommunicationContext(String conversationId)
            implements CustomCommunicationContext {

        @Override
        public String channel() {
            return "mychannel";
        }
    }
}
