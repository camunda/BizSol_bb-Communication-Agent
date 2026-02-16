package io.camunda.bizsol.bb.comm_agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties = {
                // disable all real workers in tests to focus on BPMN test
                "camunda.client.worker.defaults.enabled=false",
        })
@CamundaSpringProcessTest
public class ReceiveMessageTest {

    private static final String PROCESS_DEFINITION_ID = "message-receiver";
    private static final String EMAIL_CONNECTOR_ID = "StartEvent_Email";
    public static final String EMAIL_INBOUND_MESSAGE_NAME = "306ec9c5-911d-4979-b781-1f6b5fc741ad";

    @Autowired
    private CamundaClient client;
    @Autowired
    private CamundaProcessTestContext processTestContext;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    RestTemplate restTemplate;

    @Test
    void shouldSendBpmnMessageWithSupportCaseVariables() {
        // given
        var supportCase =
                SupportCase.builder()
                        .subject("Test Multichannel")
                        .request("Hi!")
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .emailAddress("example@camunda.com")
                                        .conversationId("ba04712f-eae7-433a-9dd4-c56286e65940")
                                        .build())
                        .build();

        // given: the processes are deployed
        client.newDeployResourceCommand()
                .addResourceFromClasspath("message-receiver.bpmn")
                .send()
                .join();

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
                .hasCompletedElements("Task_SendBPMNMessage")
                .hasLocalVariableSatisfies(
                        "Task_SendBPMNMessage",
                        "variables",
                        JsonNode.class,
                        variables -> {
                            SupportCase sendSupportCase =
                                    objectMapper.readValue(
                                            variables.get("supportCase").toString(),
                                            SupportCase.class);
                            assertThat(sendSupportCase).isEqualTo(supportCase);
                            assertThat(variables.get("messageName").toString()).isEqualTo("UnmatchedCommunicationReceived");
                            String correlationKey = variables.get("correlationKey").toString();
                            assertThat(UUID.fromString(correlationKey).toString()).withFailMessage("Invalid UUID").isEqualTo(correlationKey);
                        });
    }

    @Test
    void shouldMapEmailInboundToSupportCase() {
        // given
        final var email = """
                {
                  "messageId": "messageId",
                  "fromAddress": "example@camunda.com",
                  "subject": "Urgent request",
                  "size": 65646,
                  "plainTextBody": "Hey how are you?\\r\\n",
                  "htmlBody": "<html>Hello</html>",
                  "headers": [
                    {
                      "key": "header1",
                      "value": "example"
                    },
                    {
                      "key": "header2",
                      "value": "test"
                    }
                  ],
                  "attachments": [],
                  "receivedDateTime": "2026-02-16T06:54:28Z"
                }
                """;


        // given: the processes are deployed
        client.newDeployResourceCommand()
                .addResourceFromClasspath("message-receiver.bpmn")
                .send()
                .join();

        // when
        client.newCorrelateMessageCommand().messageName(EMAIL_INBOUND_MESSAGE_NAME).withoutCorrelationKey().variables(email).send().join();

        // then
        final SupportCase expectedSupportCase = SupportCase.builder()
                .subject("Urgent request")
                .request("Hey how are you?\r\n")
                .communicationContext(
                        new EmailCommunicationContext("messageId", "example@camunda.com")
                ).build();
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements("Task_SendBPMNMessage")
                .hasLocalVariableSatisfies(
                        "Task_SendBPMNMessage",
                        "variables",
                        JsonNode.class,
                        variables -> {
                            SupportCase sendSupportCase =
                                    objectMapper.readValue(
                                            variables.get("supportCase").toString(),
                                            SupportCase.class);
                            assertThat(sendSupportCase).isEqualTo(expectedSupportCase);
                        });
    }
}
