package io.camunda.bizsol.bb.comm_agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.testutil.EmailTestUtil;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
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

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.testcontainers.Testcontainers.exposeHostPorts;

@SpringBootTest(properties = {"CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX=''"})
@CamundaSpringProcessTest
@Testcontainers
public class InboundIT {

    private static final String PROCESS_DEFINITION_ID = "message-receiver";
    private static final int GREENMAIL_IMAP_PORT = 3143;
    private static final int GREENMAIL_SMTP_PORT = 3025;
    private static final String GREENMAIL_USERNAME = "node";
    private static final String GREENMAIL_PASSWORD = "password";
    public static final String SUPPORT_CASE_SUBJECT = "Sample Support Request";
    public static final String SUPPORT_CASE_REQUEST = "Hello, I need help with my account.\n";

    @Autowired
    private CamundaClient client;
    @Autowired
    private CamundaProcessTestContext processTestContext;
    @Autowired
    private ObjectMapper objectMapper;

    @Container
    static GenericContainer<?> greenmail =
            new GenericContainer<>(DockerImageName.parse("greenmail/standalone"))
                    // GreenMail standalone uses test ports with an offset of 3000: SMTP 3025, IMAP
                    // 3143.
                    .withEnv(
                            "GREENMAIL_OPTS",
                            "-Dgreenmail.setup.test.all -Dgreenmail.hostname=0.0.0.0 -Dgreenmail.users=node:password")
                    .withExposedPorts(GREENMAIL_SMTP_PORT, GREENMAIL_IMAP_PORT, 8080)
                    .waitingFor(Wait.forListeningPort());

    @BeforeEach
    void setup() {
        exposeHostPorts(greenmail.getMappedPort(GREENMAIL_IMAP_PORT));
    }

    @Test
    public void incomingEmailShouldBeProcessed() throws InterruptedException {
        // setup
        client.newDeployResourceCommand()
                .addResourceFromClasspath("message-receiver.bpmn")
                .send()
                .join();

        // when
        EmailTestUtil.sendSampleEmail(
                greenmail.getHost(),
                greenmail.getMappedPort(GREENMAIL_SMTP_PORT),
                "example@camunda.com",
                GREENMAIL_USERNAME,
                SUPPORT_CASE_SUBJECT,
                SUPPORT_CASE_REQUEST,
                "fixtures/sample.pdf");

        // then
        final SupportCase expectedSupportCase =
                SupportCase.builder()
                        .subject(SUPPORT_CASE_SUBJECT)
                        .request(SUPPORT_CASE_REQUEST)
                        .communicationContext(
                                new EmailCommunicationContext("messageId", "example@camunda.com"))
                        .build();
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
                            Assertions.assertThat(sendSupportCase)
                                    .isEqualTo(expectedSupportCase)
                                    .usingRecursiveComparison(
                                            RecursiveComparisonConfiguration.builder()
                                                    .withIgnoredFields("messageId")
                                                    .build());
                        });
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        greenmail.start();
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_SERVER",
                () -> "host.docker.internal");
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PORT",
                () -> greenmail.getMappedPort(GREENMAIL_IMAP_PORT));
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_USERNAME",
                () -> GREENMAIL_USERNAME);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PASSWORD",
                () -> GREENMAIL_PASSWORD);
    }
}
