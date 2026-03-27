package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "camunda.client.worker.defaults.enabled=false",
        })
@CamundaSpringProcessTest
public class ReceiveMessageTest {

    private static final String PROCESS_DEFINITION_ID = "message-receiver";
    private static final String MESSAGE_RECEIVE_FILE = "camunda-artifacts/message-receiver.bpmn";
    private static final String SEND_MESSAGE_CONNECTOR_ELEMENT_ID =
            "Event_SendCustomerCommunicationReceived";
    private final String CORRELATION_KEY = "correlationKeyValue";

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should send BPMN message with support case and correlationKey")
    void shouldSendBpmnMessageWithSupportCaseVariablesAndCorrelationKey()
            throws FileNotFoundException {
        // given
        var supportCase =
                SupportCase.builder()
                        .subject("Test Multichannel")
                        .request("Hi!")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 17, 12, 0))
                        .attachments(Collections.emptyList())
                        .correlationKey(CORRELATION_KEY)
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .emailAddress("example@camunda.com")
                                        .conversationId("ba04712f-eae7-433a-9dd4-c56286e65940")
                                        .build())
                        .build();

        // given: the processes are deployed
        BpmnModelInstance messageReceiveModel =
                BpmnFile.replace(
                        MESSAGE_RECEIVE_FILE,
                        // Add none start event
                        replace(
                                "</bpmn:process>",
                                """
                                            <bpmn:startEvent id="Event_1jp0lym">
                                              <bpmn:outgoing>Flow_1f517si</bpmn:outgoing>
                                            </bpmn:startEvent>
                                            <bpmn:sequenceFlow id="Flow_1f517si" sourceRef="Event_1jp0lym" targetRef="Gateway_0zc209k" />
                                          </bpmn:process>
                                        """));
        client.newDeployResourceCommand()
                .addProcessModel(messageReceiveModel, MESSAGE_RECEIVE_FILE)
                .send()
                .join();

        // and: mock case-matching sub-process
        processTestContext.mockChildProcess(
                "case-matching", Map.of("correlationKey", CORRELATION_KEY));

        // when
        final ProcessInstanceEvent processInstance =
                client.newCreateInstanceCommand()
                        .bpmnProcessId(PROCESS_DEFINITION_ID)
                        .latestVersion()
                        .variables(Map.of("supportCase", supportCase))
                        .send()
                        .join();

        // then
        assertThat(processInstance)
                .isCompleted()
                .hasCompletedElements(SEND_MESSAGE_CONNECTOR_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "variables",
                        JsonNode.class,
                        variables -> {
                            SupportCase sendSupportCase =
                                    objectMapper.readValue(
                                            variables.get("supportCase").toString(),
                                            SupportCase.class);
                            assertThat(sendSupportCase).isEqualTo(supportCase);
                        });

        assertThat(processInstance)
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName).isEqualTo("CustomerCommunicationReceived"));

        assertThat(processInstance)
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "correlationKey",
                        String.class,
                        correlationKey -> assertThat(correlationKey).isEqualTo(CORRELATION_KEY));
    }
}
