package io.camunda.bizsol.bb.comm_agent;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.Attachment;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import io.camunda.bizsol.bb.comm_agent.util.BpmnFile;
import io.camunda.bizsol.bb.comm_agent.util.EmailTestUtil;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.FileNotFoundException;
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

@SpringBootTest(
        properties = {
            "CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX=''",
            "camunda.process-test.coverage.reportDirectory: target/coverage-report-integration"
        })
@CamundaSpringProcessTest
@Testcontainers
public class InboundIT {

    private static final String PROCESS_DEFINITION_ID = "message-receiver";
    private static final String MESSAGE_RECEIVE_FILE = "camunda-artifacts/message-receiver.bpmn";
    private static final String SEND_MESSAGE_CONNECTOR_ELEMENT_ID =
            "Event_SendCustomerCommunicationReceived";
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
    public void incomingEmailShouldBeProcessed()
            throws InterruptedException, FileNotFoundException {
        // setup
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
                                        """),
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
        assertThatProcessInstance(byProcessId(PROCESS_DEFINITION_ID))
                .isCompleted()
                .hasCompletedElements(SEND_MESSAGE_CONNECTOR_ELEMENT_ID)
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "correlationKey",
                        JsonNode.class,
                        correlationKey -> {
                            assertThat(correlationKey.asText())
                                    .isEqualTo(TEST_EMAIL_ADDRESS + "/UNKNOWN");
                        })
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
                        "messageName",
                        JsonNode.class,
                        messageName -> {
                            assertThat(messageName.asText())
                                    .isEqualTo("CustomerCommunicationReceived");
                        })
                .hasLocalVariableSatisfies(
                        SEND_MESSAGE_CONNECTOR_ELEMENT_ID,
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
