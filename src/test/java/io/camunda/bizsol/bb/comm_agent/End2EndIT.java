package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX=''",
            "camunda.process-test.coverage.reportDirectory: target/coverage-report-integration"
        })
@CamundaSpringProcessTest
@Testcontainers
public class End2EndIT {

    private static final String COMMUNICATION_AGENT_PROCESS_DEFINITION_ID =
            "customer-communication-agent";
    private static final String PROCESS_DEFINITION_ID = "e2e_communication_01";
    private static final String MESSAGE_RECEIVE_FILE = "camunda-artifacts/message-receiver.bpmn";
    private static final String COMMUNICATION_AGENT_RECEIVE_FILE =
            "camunda-artifacts/communication-agent.bpmn";
    private static final int GREENMAIL_IMAP_PORT = 3143;
    private static final int GREENMAIL_SMTP_PORT = 3025;
    private static final String GREENMAIL_AGENT_USERNAME = "node";
    private static final String GREENMAIL_AGENT_PASSWORD = "password";
    private static final String GREENMAIL_CUSTOMER_USERNAME = "customer";
    private static final String GREENMAIL_CUSTOMER_PASSWORD = "password";

    @Autowired private CamundaClient client;
    @Autowired private CamundaProcessTestContext processTestContext;
    @Autowired private ObjectMapper objectMapper;

    @Container
    static GenericContainer<?> greenmail =
            new GenericContainer<>(DockerImageName.parse("greenmail/standalone"))
                    // GreenMail standalone uses test ports with an offset of 3000: SMTP 3025, IMAP
                    // 3143.
                    .withEnv(
                            "GREENMAIL_OPTS",
                            "-Dgreenmail.setup.test.all -Dgreenmail.hostname=0.0.0.0 -Dgreenmail.users=node:password,customer:password")
                    .withExposedPorts(GREENMAIL_SMTP_PORT)
                    .waitingFor(Wait.forListeningPort());

    static String getGreenmailIp() {
        return greenmail
                .getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress();
    }

    @BeforeEach
    void deployProcess() throws FileNotFoundException {
        deployInbound();
        deployCommunicationAgent();

        // Mock outbound sub-processs
        processTestContext.mockChildProcess(
                "message-sender",
                Map.of(
                        "toolCallResult",
                        """
                                {"status": "success"}
                                """));
    }

    @Test
    public void startEnd2EndTest() throws FileNotFoundException, InterruptedException {
        client.newDeployResourceCommand()
                .addResourceFromClasspath("bpmns/testprocess.bpmn")
                .send()
                .join();

        // when
        client.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_DEFINITION_ID)
                .latestVersion()
                .send()
                .join();

        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID)).isCompleted();
    }

    private void deployInbound() throws FileNotFoundException {
        BpmnModelInstance messageReceiveModel =
                BpmnFile.replace(
                        MESSAGE_RECEIVE_FILE,
                        // Add email connector settings
                        replace(
                                """
                                        <zeebe:property name="authentication.username" value="" />
                                        """,
                                """
                                        <zeebe:property name="authentication.username" value="{{secrets.INBOUND_IMAP_USERNAME}}" />
                                        """),
                        replace(
                                """
                                         <zeebe:property name="authentication.password" value="" />
                                         """,
                                """
                                         <zeebe:property name="authentication.password" value="{{secrets.INBOUND_IMAP_PASSWORD}}" />
                                         """),
                        replace(
                                """
                                        <zeebe:property name="data.imapConfig.imapHost" value="" />
                                        """,
                                """
                                        <zeebe:property name="data.imapConfig.imapHost" value="{{secrets.INBOUND_IMAP_SERVER}}" />
                                        """),
                        replace(
                                """
                                        <zeebe:property name="data.imapConfig.imapPort" value="" />
                                        """,
                                """
                                        <zeebe:property name="data.imapConfig.imapPort" value="{{secrets.INBOUND_IMAP_PORT}}" />
                                        """));
        client.newDeployResourceCommand()
                .addProcessModel(messageReceiveModel, MESSAGE_RECEIVE_FILE)
                .addResourceFromClasspath("case-matching.bpmn")
                .addResourceFromClasspath("intent_matching.dmn")
                .send()
                .join();
    }

    private void deployCommunicationAgent() throws FileNotFoundException {
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
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        greenmail.start();
        // IMAP
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_SERVER",
                End2EndIT::getGreenmailIp);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PORT",
                () -> GREENMAIL_IMAP_PORT);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_USERNAME",
                () -> GREENMAIL_AGENT_USERNAME);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PASSWORD",
                () -> GREENMAIL_AGENT_PASSWORD);
        // SMPT
        registry.add(
                "camunda.process-test.connectors-secrets.E2E_SMTP_SERVER",
                End2EndIT::getGreenmailIp);
        registry.add(
                "camunda.process-test.connectors-secrets.E2E_SMTP_PORT", () -> GREENMAIL_SMTP_PORT);
        registry.add(
                "camunda.process-test.connectors-secrets.E2E_SMTP_USERNAME",
                () -> GREENMAIL_CUSTOMER_USERNAME);
        registry.add(
                "camunda.process-test.connectors-secrets.E2E_SMTP_PASSWORD",
                () -> GREENMAIL_CUSTOMER_PASSWORD);
    }
}
