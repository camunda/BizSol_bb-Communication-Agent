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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"camunda.client.worker.defaults.enabled=false"})
@CamundaSpringProcessTest
public class CaseMatchingTest {

    private static final String PROCESS_DEFINITION_ID = "case-matching";
    private static final String START_MESSAGE_NAME = "UnmatchedCommunicationReceived";
    private static final String CUSTOM_CONVERSATION_ID = "4711";
    private static final String VARIABLE_CORRELATION_KEY = "correlationKey";

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;

    @BeforeEach
    void deployProcess() {
        client.newDeployResourceCommand()
                .addResourceFromClasspath("case-matching.bpmn")
                .addResourceFromClasspath("intent_matching.dmn")
                .send()
                .join();
    }

    @ParameterizedTest
    @DisplayName("FEEL: Use email based communication")
    @CsvSource({
        "Email case,customer@camunda.com,customer@camunda.com/UNKNOWN",
        "V123456789: Email case,customer@camunda.com,V123456789/UNKNOWN",
        "Dog insurance,customer@camunda.com,customer@camunda.com/DOG_INSURANCE",
        "Car insurance,customer@camunda.com,customer@camunda.com/CAR_INSURANCE",
        "V123456789: Dog insurance,customer@camunda.com,V123456789/DOG_INSURANCE",
        "V123456789: Car insurance,customer@camunda.com,V123456789/CAR_INSURANCE",
    })
    void emailBasedCommunicationUsingFeel(
            String subject, String emailAddress, String expectedCorrelationKey) {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject(subject)
                        .request("Need help")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 20, 9, 30))
                        .attachments(Collections.emptyList())
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .conversationId("conv-email-001")
                                        .emailAddress(emailAddress)
                                        .build())
                        .build();
        // when
        startWithMessage(supportCase, true);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasVariableSatisfies(
                        VARIABLE_CORRELATION_KEY,
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(expectedCorrelationKey));
    }

    @ParameterizedTest
    @DisplayName("FEEL: Use phone based communication")
    @CsvSource({
        "Phone case,800-654-1984,800-654-1984/UNKNOWN",
        "V987654321: Email case,800-654-1984 ,V987654321/UNKNOWN",
        "Dog insurance,800-654-1984,800-654-1984/DOG_INSURANCE",
        "Car insurance,800-654-1984,800-654-1984/CAR_INSURANCE",
        "V987654321: Dog insurance,800-654-1984,V987654321/DOG_INSURANCE",
        "V987654321: Car insurance,800-654-1984,V987654321/CAR_INSURANCE",
    })
    void phoneBasedCommunicationUsingFeel(
            String subject, String phoneNumber, String expectedCorrelationKey) {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject(subject)
                        .request("Need help")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 20, 9, 30))
                        .attachments(Collections.emptyList())
                        .communicationContext(
                                PhoneCommunicationContext.builder()
                                        .conversationId("conv-phone-001")
                                        .phoneNumber(phoneNumber)
                                        .build())
                        .build();
        // when
        startWithMessage(supportCase, true);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasVariableSatisfies(
                        VARIABLE_CORRELATION_KEY,
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(expectedCorrelationKey));
    }

    @ParameterizedTest
    @DisplayName("FEEL: Use custom communication")
    @CsvSource({
        "Phone case,conversation-4711,conversation-4711/UNKNOWN",
        "V987654321: Email case,conversation-4711,V987654321/UNKNOWN",
        "Dog insurance,conversation-4711,conversation-4711/DOG_INSURANCE",
        "Car insurance,conversation-4711,conversation-4711/CAR_INSURANCE",
        "V987654321: Dog insurance,conversation-4711,V987654321/DOG_INSURANCE",
        "V987654321: Car insurance,conversation-4711,V987654321/CAR_INSURANCE",
    })
    void customCommunicationUsingFeel(
            String subject, String conversationId, String expectedCorrelationKey) {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject(subject)
                        .request("Need help")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 20, 9, 30))
                        .attachments(Collections.emptyList())
                        .communicationContext(new MyCommunicationContext(conversationId))
                        .build();
        // when
        startWithMessage(supportCase, true);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasVariableSatisfies(
                        VARIABLE_CORRELATION_KEY,
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(expectedCorrelationKey));
    }

    @Test
    @DisplayName("Worker: Use result from worker as correlationKey")
    void shouldSatisfyCorrelationKeyFallback() {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Phone case")
                        .request("Please call me back")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 21, 11, 15))
                        .attachments(Collections.emptyList())
                        .communicationContext(new MyCommunicationContext("Dummy"))
                        .build();
        processTestContext
                .mockJobWorker("CaseMatching")
                .thenComplete(Map.of("correlationKey", CUSTOM_CONVERSATION_ID));

        // when
        startWithMessage(supportCase, false);

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasVariableSatisfies(
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(correlationKey).isEqualTo(CUSTOM_CONVERSATION_ID));
    }

    private void startWithMessage(SupportCase supportCase, boolean useFeel) {
        client.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_DEFINITION_ID)
                .latestVersion()
                .variables(Map.of("supportCase", supportCase, "use_feel", useFeel))
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
