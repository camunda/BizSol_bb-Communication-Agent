package io.camunda.bizsol.bb.comm_agent.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportCaseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldMarshalAndUnmarshalSupportCaseWithEmailContext() throws Exception {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Subject A")
                        .request("Please help")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 17, 12, 34, 56))
                        .communicationContext(
                                EmailCommunicationContext.builder()
                                        .emailAddress("example@camunda.com")
                                        .conversationId("conv-123")
                                        .build())
                        .attachments(
                                List.of(
                                        Attachment.builder()
                                                .documentId("doc-1")
                                                .storeId("store-9")
                                                .contentHash("hash-xyz")
                                                .metadata(
                                                        new Attachment.Metadata(
                                                                "note.txt", "text/plain", 12L))
                                                .build()))
                        .build();

        // when
        String json = objectMapper.writeValueAsString(supportCase);
        SupportCase roundTrip = objectMapper.readValue(json, SupportCase.class);
        JsonNode root = objectMapper.readTree(json);

        // then
        assertAll(
                () -> assertThat(roundTrip).isEqualTo(supportCase),
                () ->
                        assertThat(root.get("communicationContext").get("channel").asText())
                                .isEqualTo("email"));
    }

    @Test
    void shouldUnmarshalSupportCaseFromJsonWithPhoneContext() throws Exception {
        // given
        String json =
                """
                        {
                          "subject":"Subject B",
                          "request":"Call me",
                          "receivedDateTime":"2026-02-18T09:00:00",
                          "communicationContext":{
                            "channel":"phone",
                            "conversationId": "conv-456",
                            "phoneNumber":"+49-40-123456"
                          },
                          "attachments":[]
                        }
                        """;

        // when
        SupportCase supportCase = objectMapper.readValue(json, SupportCase.class);
        PhoneCommunicationContext context =
                (PhoneCommunicationContext) supportCase.communicationContext();

        // then
        assertAll(
                () -> assertThat(supportCase.subject()).isEqualTo("Subject B"),
                () ->
                        assertThat(supportCase.communicationContext())
                                .isInstanceOf(PhoneCommunicationContext.class),
                () -> assertThat(context.channel()).isEqualTo("phone"),
                () -> assertThat(context.conversationId()).isEqualTo("conv-456"),
                () -> assertThat(context.phoneNumber()).isEqualTo("+49-40-123456"));
    }

    @Test
    void shouldMarshalAndUnmarshalSupportCaseWithPhoneContext() throws Exception {
        // given
        SupportCase supportCase =
                SupportCase.builder()
                        .subject("Subject C")
                        .request("Please call back")
                        .receivedDateTime(LocalDateTime.of(2026, 2, 19, 8, 15))
                        .communicationContext(
                                PhoneCommunicationContext.builder()
                                        .conversationId("conv-789")
                                        .phoneNumber("+49-40-123456")
                                        .build())
                        .attachments(List.of())
                        .build();

        // when
        String json = objectMapper.writeValueAsString(supportCase);
        SupportCase roundTrip = objectMapper.readValue(json, SupportCase.class);
        JsonNode root = objectMapper.readTree(json);

        // then
        assertAll(
                () -> assertThat(roundTrip).isEqualTo(supportCase),
                () ->
                        assertThat(root.get("communicationContext").get("channel").asText())
                                .isEqualTo("phone"));
    }

    @Test
    void shouldUnmarshalSupportCaseFromJsonWithEmailContext() throws Exception {
        // given
        String json =
                """
                        {
                            "request":"Hello, I need help with my account.",
                            "subject":"Sample Support Request",
                            "communicationContext":{
                                "channel":"email",
                                "conversationId":"conv-321",
                                "emailAddress":"example@camunda.com"},
                                "receivedDateTime":"2026-02-23T07:50:03Z",
                                "attachments":[{
                                    "storeId":"in-memory",
                                    "documentId":"0cf78129-f1eb-4edb-ac5e-26b747768810",
                                    "contentHash":"9642c2a5bd34586cdb83e0f49588fc7c39194fb540b112d0ebc32a00fac693f2",
                                    "metadata":{
                                        "contentType":"APPLICATION/PDF",
                                        "size":619,
                                        "fileName":"sample.pdf"
                                    }
                                }
                                ]
                        }
                        """;

        // when
        SupportCase supportCase = objectMapper.readValue(json, SupportCase.class);
        EmailCommunicationContext context =
                (EmailCommunicationContext) supportCase.communicationContext();

        // then
        assertAll(
                () -> assertThat(supportCase.subject()).isEqualTo("Sample Support Request"),
                () ->
                        assertThat(supportCase.communicationContext())
                                .isInstanceOf(EmailCommunicationContext.class),
                () -> assertThat(context.channel()).isEqualTo("email"),
                () -> assertThat(context.conversationId()).isEqualTo("conv-321"),
                () -> assertThat(context.emailAddress()).isEqualTo("example@camunda.com"));
    }
}
