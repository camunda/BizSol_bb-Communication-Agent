package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
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

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldSendBpmnMessageWithSupportCaseVariables() {
        // given
        var supportCase =
                SupportCase.builder()
                        .subject("Test Multichannel")
                        .request("Hi!")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 17, 12, 0))
                        .attachments(Collections.emptyList())
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
                        });

        assertThat(processInstance)
                .hasLocalVariableSatisfies(
                        "Task_SendBPMNMessage",
                        "messageName",
                        String.class,
                        messageName ->
                                assertThat(messageName)
                                        .isEqualTo("UnmatchedCommunicationReceived"));

        assertThat(processInstance)
                .hasLocalVariableSatisfies(
                        "Task_SendBPMNMessage",
                        "correlationKey",
                        String.class,
                        correlationKey ->
                                assertThat(UUID.fromString(correlationKey).toString())
                                        .withFailMessage("Invalid UUID")
                                        .isEqualTo(correlationKey));
    }
}
