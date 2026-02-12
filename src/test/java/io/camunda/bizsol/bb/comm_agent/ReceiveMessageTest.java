package io.camunda.bizsol.bb.comm_agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

@SpringBootTest(
        properties = {
                // disable all real workers in tests to focus on BPMN test
                "camunda.client.worker.defaults.enabled=false"
        })
@CamundaSpringProcessTest
public class ReceiveMessageTest {
    @Autowired
    private CamundaClient client;
    @Autowired
    private CamundaProcessTestContext processTestContext;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldEmitUnmatchedCommunicationReceivedMessage() throws JsonProcessingException {
        // setup
        // CamundaAssert.setAssertionTimeout(Duration.ofMinutes(1));

        // given
        var supportCase =
                SupportCase.builder()
                        .subject("Test Multichannel")
                        .request("Hi!")
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .emailAddress("michael.meier@holisticon.de")
                                        .conversationId("ba04712f-eae7-433a-9dd4-c56286e65940")
                                        .build())
                        .build();

        // given: the processes are deployed
        client.newDeployResourceCommand()
                .addResourceFromClasspath("message-receiver.bpmn")
                // .addResourceFromClasspath("case-matching.bpmn")
                // .addResourceFromClasspath("communication-agent.bpmn")
                // .addResourceFromClasspath("message-sender.bpmn")
                .send()
                .join();

        // when
        final ProcessInstanceEvent processInstance =
                client.newCreateInstanceCommand()
                        .bpmnProcessId("message-receiver")
                        .latestVersion()
                        .variables(Map.of("supportCase", supportCase))
                        .send()
                        .join();

        // then
        CamundaAssert.assertThat(processInstance)
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
                            Assertions.assertThat(sendSupportCase).isEqualTo(supportCase);
                        });
        // and
        /**
         * CamundaAssert.assertThatProcessInstance(
         * ProcessInstanceSelectors.byProcessId("Process_CaseMatching")) .isCompleted()
         * .hasCorrelatedMessage("UnmatchedCommunicationReceived")
         * .hasCompletedElements("Task_CaseMatching"); // and
         * CamundaAssert.assertThatProcessInstance(
         * ProcessInstanceSelectors.byProcessId("customer-communication-agent")) .isActive()
         * .hasCorrelatedMessage("CustomerCommunicationReceived", "michael.meier@holisticon.de")
         * .hasActiveElements("SubProcess_CommunicationAgent");
         */
    }
}
