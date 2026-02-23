package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.Attachment;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.testutil.EmailTestUtil;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.LocalDateTime;
import java.util.Collections;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
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

@SpringBootTest(properties = {"CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX=''"})
@CamundaSpringProcessTest
@Testcontainers
public class InboundIT {

    private static final String CASE_MATCHING_PROCESS_DEFINITION_ID = "case-matching";
    private static final int GREENMAIL_IMAP_PORT = 3143;
    private static final int GREENMAIL_SMTP_PORT = 3025;
    private static final String GREENMAIL_USERNAME = "node";
    private static final String GREENMAIL_PASSWORD = "password";
    private static final String TEST_EMAIL_ADDRESS = "example@camunda.com";
    private static final String TEST_MESSAGE_ID = "messageId";

    private static final String SUPPORT_CASE_SUBJECT = "Sample Support Request";
    private static final String SUPPORT_CASE_REQUEST = "Hello, I need help with my account.";

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
                            "-Dgreenmail.setup.test.all -Dgreenmail.hostname=0.0.0.0 -Dgreenmail.users=node:password")
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

    @Test
    public void incomingEmailShouldBeProcessed() throws InterruptedException {
        // setup
        client.newDeployResourceCommand()
                .addResourceFromClasspath("message-receiver.bpmn")
                .addResourceFromClasspath("case-matching.bpmn")
                .send()
                .join();

        // when
        EmailTestUtil.sendSampleEmail(
                greenmail.getHost(),
                greenmail.getMappedPort(GREENMAIL_SMTP_PORT),
                TEST_EMAIL_ADDRESS,
                GREENMAIL_USERNAME,
                SUPPORT_CASE_SUBJECT,
                SUPPORT_CASE_REQUEST,
                "fixtures/sample.pdf");

        // then
        EmailCommunicationContext expectedCommunicationChannel =
                new EmailCommunicationContext(TEST_MESSAGE_ID, TEST_EMAIL_ADDRESS);
        final SupportCase expectedSupportCase =
                SupportCase.builder()
                        .subject(SUPPORT_CASE_SUBJECT)
                        .request(SUPPORT_CASE_REQUEST)
                        .receivedDateTime(LocalDateTime.now())
                        .attachments(
                                Collections.singletonList(
                                        Attachment.builder()
                                                .contentHash(
                                                        "9642c2a5bd34586cdb83e0f49588fc7c39194fb540b112d0ebc32a00fac693f2")
                                                .documentId("dummy")
                                                .storeId("in-memory")
                                                .metadata(
                                                        Attachment.Metadata.builder()
                                                                .fileName("sample.pdf")
                                                                .size(619L)
                                                                .contentType("APPLICATION/PDF")
                                                                .build())
                                                .build()))
                        .communicationContext(expectedCommunicationChannel)
                        .build();

        // then
        assertThatProcessInstance(byProcessId(CASE_MATCHING_PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements("Event_SendCustomerCommunicationReceived")
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "correlationKey",
                        JsonNode.class,
                        correlationKey -> {
                            assertThat(correlationKey.asText())
                                    .isEqualTo(expectedCommunicationChannel.emailAddress());
                        })
                .hasLocalVariableSatisfies(
                        "Event_SendCustomerCommunicationReceived",
                        "variables",
                        JsonNode.class,
                        variables -> {
                            final SupportCase actualSupportCase =
                                    objectMapper.readValue(
                                            variables.get("supportCase").toString(),
                                            SupportCase.class);
                            assertThat(actualSupportCase)
                                    .usingRecursiveComparison(
                                            // Exclude dynamic values from comparison
                                            RecursiveComparisonConfiguration.builder()
                                                    .withIgnoredFields(
                                                            "messageId",
                                                            "receivedDateTime",
                                                            "communicationContext.conversationId",
                                                            "attachments.documentId")
                                                    .build())
                                    .isEqualTo(expectedSupportCase);
                        });
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        greenmail.start();
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_SERVER",
                InboundIT::getGreenmailIp);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PORT",
                () -> GREENMAIL_IMAP_PORT);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_USERNAME",
                () -> GREENMAIL_USERNAME);
        registry.add(
                "camunda.process-test.connectors-secrets.INBOUND_IMAP_PASSWORD",
                () -> GREENMAIL_PASSWORD);
    }
}
