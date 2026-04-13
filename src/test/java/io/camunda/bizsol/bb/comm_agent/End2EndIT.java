package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.CamundaAssert.setAssertionTimeout;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(End2EndIT.class);

    private static final String COMMUNICATION_AGENT_PROCESS_DEFINITION_ID =
            "customer-communication-agent";
    private static final String PROCESS_DEFINITION_ID = "e2e_communication_01";
    private static final String MESSAGE_RECEIVE_FILE = "camunda-artifacts/message-receiver.bpmn";
    private static final String MESSAGE_SENDER_FILE = "camunda-artifacts/message-sender.bpmn";
    private static final String COMMUNICATION_AGENT_RECEIVE_FILE =
            "camunda-artifacts/communication-agent.bpmn";
    private static final int GREENMAIL_IMAP_PORT = 3143;
    private static final int GREENMAIL_SMTP_PORT = 3025;
    private static final String GREENMAIL_AGENT_USERNAME = "node@localhost";
    private static final String GREENMAIL_AGENT_PASSWORD = "password";
    private static final String GREENMAIL_CUSTOMER_USERNAME = "customer@localhost";
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
                            "-Dgreenmail.setup.test.all -Dgreenmail.hostname=0.0.0.0 -Dgreenmail.users=node@localhost:password,customer@localhost:password")
                    //  .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("greenmail"))
                    .withExposedPorts(GREENMAIL_SMTP_PORT)
                    .withReuse(false)
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
        deployOutbound();
        setAssertionTimeout(Duration.ofSeconds(30));
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

        // and
        fakeCommunicationRequiredFromBusinessAgent();

        // then
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID)).isCompleted();
    }

    /** Have to be reaplced by the real business agent logic in the future. */
    private void fakeCommunicationRequiredFromBusinessAgent() {
        assertThatProcessInstance(byProcessId(COMMUNICATION_AGENT_PROCESS_DEFINITION_ID))
                .hasActiveElements("Tool_WAIT")
                .isActive();
        client.newPublishMessageCommand()
                .messageName("CustomerCommunicationRequired")
                .correlationKey("V123456789/ETW")
                .variables(
                        Map.of(
                                "communicationContent",
                                """
                                {
                                    "subject":"RE: Email case",
                                    "text":"Hi!"
                                }
                                """,
                                "communicationContext",
                                """
                                {
                                    "channel":"email",
                                    "emailAddress":"customer@localhost",
                                    "conversationId":"<968036FE-0C58-49E8-920A-A1C02F44D85E>"
                                }
                                """))
                .send()
                .join();
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

    private void deployOutbound() {
        BpmnModelInstance messageReceiveModel =
                BpmnFile.replace(
                        MESSAGE_SENDER_FILE,
                        // Add email connector settings
                        replace(
                                """
                                        <zeebe:input target="authentication.username" />
                                        """,
                                """
                                        <zeebe:input target="authentication.username" source="{{secrets.OUTBOUND_SMTP_USERNAME}}" />
                                        """),
                        replace(
                                """
                                         <zeebe:input target="authentication.password" />
                                         """,
                                """
                                         <zeebe:input target="authentication.password" source="{{secrets.OUTBOUND_SMTP_PASSWORD}}" />
                                         """),
                        replace(
                                """
                                        <zeebe:input target="data.smtpConfig.smtpHost" />
                                        """,
                                """
                                        <zeebe:input target="data.smtpConfig.smtpHost" source="{{secrets.OUTBOUND_SMTP_SERVER}}" />
                                        """),
                        replace(
                                """
                                        <zeebe:input target="data.smtpConfig.smtpPort" />
                                        """,
                                """
                                        <zeebe:input target="data.smtpConfig.smtpPort" source="{{secrets.OUTBOUND_SMTP_PORT}}" />
                                        """),
                        replace(
                                """
                                        <zeebe:input target="data.smtpAction.from" />
                                        """,
                                """
                                        <zeebe:input target="data.smtpAction.from" source="{{secrets.OUTBOUND_SMTP_USERNAME}}" />
                                        """));
        client.newDeployResourceCommand()
                .addProcessModel(messageReceiveModel, MESSAGE_SENDER_FILE)
                .send()
                .join();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        greenmail.start();
        // INBOUND IMAP
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
        // OUTBOUND SMPT
        registry.add(
                "camunda.process-test.connectors-secrets.OUTBOUND_SMTP_SERVER",
                End2EndIT::getGreenmailIp);
        registry.add(
                "camunda.process-test.connectors-secrets.OUTBOUND_SMTP_PORT",
                () -> GREENMAIL_SMTP_PORT);
        registry.add(
                "camunda.process-test.connectors-secrets.OUTBOUND_SMTP_USERNAME",
                () -> GREENMAIL_AGENT_USERNAME);
        registry.add(
                "camunda.process-test.connectors-secrets.OUTBOUND_SMTP_PASSWORD",
                () -> GREENMAIL_AGENT_PASSWORD);
        // E2E IN AND OUT
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
