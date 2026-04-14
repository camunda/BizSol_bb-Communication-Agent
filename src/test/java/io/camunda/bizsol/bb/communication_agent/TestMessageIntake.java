package io.camunda.bizsol.bb.communication_agent;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.Deployment;
import io.camunda.client.api.response.CorrelateMessageResponse;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import java.io.FileNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "camunda.client.worker.defaults.enabled=false",
        })
@CamundaSpringProcessTest
public class TestMessageIntake {

    private static final String PROCESS_DEFINITION_ID = "message-intake";
    private static final String SEND_MESSAGE_CONNECTOR_ELEMENT_ID =
            "Event_PropagateMessageToCommAgent";
    private final String CORRELATION_KEY = "id_customer_1";

    /**
     * Minimal Spring Boot application used exclusively by the test context.
     * @Deployment deploys all Camunda artifacts found under camunda-artifacts/ on the classpath
     * (populated from camunda-artifacts/ via Maven testResources) before each test method.
     */
    @SpringBootApplication
    @Deployment(resources = {"classpath*:/camunda-artifacts/**/*.bpmn", "classpath*:/camunda-artifacts/**/*.dmn"})
    static class TestApplication {}

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;

    @Test
    @DisplayName("Should send BPMN message with full customer context")
    void shouldSendBpmnMessageWithFullCustomerContext()
            throws FileNotFoundException {

        // when
        final CorrelateMessageResponse response =
                client.newCorrelateMessageCommand()
                        .messageName("message-customer-to-intake")
                        .correlationKey("")
                        .variables(
                                Map.of(
                                        "testChannel", "email",
                                        "testFrom", "jane-doe@email.com",
                                        "testSubject", "Inquiry about math problem",
                                        "testMessage", "What is 2+2?"))
                        .send()
                        .join();

        // then
        assertThatProcessInstance(
                        byKey(response.getProcessInstanceKey())
                                .and(byProcessId(PROCESS_DEFINITION_ID)))
                .withAssertionTimeout(Duration.ofMinutes(2))
                .isCompleted()
                .hasCompletedElements(SEND_MESSAGE_CONNECTOR_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "correlationKey",
                        String.class,
                        correlationKey -> assertThat(correlationKey).isEqualTo(CORRELATION_KEY))
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            assertThat(variables.at("/customerContext/id").asText())
                                    .isEqualTo(CORRELATION_KEY);
                            assertThat(variables.at("/customerContext/contactEmail").asText())
                                    .isEqualTo("jane-doe@email.com");
                            assertThat(variables.at("/customerContext/contactSms").asText())
                                    .isEqualTo("+12025556439");
                            assertThat(variables.at("/customerContext/contactChat").asText())
                                    .isEqualTo("jane-doe");
                            assertThat(variables.at("/customerContext/name").asText())
                                    .isEqualTo("Jane Doe");
                            assertThat(variables.at("/customerContext/availableChannels").isArray())
                                    .isTrue();
                            assertThat(variables.at("/messageContext/channel").asText())
                                    .isEqualTo("email");
                            assertThat(variables.at("/messageContext/from").asText())
                                    .isEqualTo("jane-doe@email.com");
                            assertThat(variables.at("/messageContext/subject").asText())
                                    .isEqualTo("Inquiry about math problem");
                            assertThat(variables.at("/messageContext/message").asText())
                                    .isEqualTo("What is 2+2?");
                        });
    }
}
