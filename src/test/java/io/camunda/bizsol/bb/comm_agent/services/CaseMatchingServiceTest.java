package io.camunda.bizsol.bb.comm_agent.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.bizsol.bb.comm_agent.models.CommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.CustomCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.PhoneCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseMatchingServiceTest {
    private CaseMatchingService serviceUnderTest;

    @BeforeEach
    void setUp() {
        serviceUnderTest = new CaseMatchingService();
    }

    @Test
    void shouldPreferContractNumberAndAppendDetectedIntent() {
        // given
        SupportCase supportCase =
                supportCase(
                        "Re: V123456789 dog insurance request",
                        EmailCommunicationContext.builder()
                                .conversationId("conv-1")
                                .emailAddress("customer@camunda.com")
                                .build());

        // when
        String correlationKey = serviceUnderTest.matchCase(supportCase).value();

        // then
        assertThat(correlationKey).isEqualTo("V123456789/DOG_INSURANCE");
    }

    @Test
    void shouldUseEmailAddressWhenNoContractNumberWasFound() {
        // given
        SupportCase supportCase =
                supportCase(
                        "Question about policy",
                        EmailCommunicationContext.builder()
                                .conversationId("conv-2")
                                .emailAddress("customer@camunda.com")
                                .build());

        // when
        String correlationKey = serviceUnderTest.matchCase(supportCase).value();

        // then
        assertThat(correlationKey).isEqualTo("customer@camunda.com/UNKNOWN");
    }

    @Test
    void shouldUsePhoneNumberWhenNoContractNumberWasFound() {
        // given
        SupportCase supportCase =
                supportCase(
                        "Need car support",
                        PhoneCommunicationContext.builder()
                                .conversationId("conv-3")
                                .phoneNumber("+1-555-0100")
                                .build());

        // when
        String correlationKey = serviceUnderTest.matchCase(supportCase).value();

        // then
        assertThat(correlationKey).isEqualTo("+1-555-0100/CAR_INSURANCE");
    }

    @Test
    void shouldFallbackToConversationIdForCustomContext() {
        // given
        SupportCase supportCase =
                supportCase("General question", new MyCommunicationContext("conversation-4711"));

        // when
        String correlationKey = serviceUnderTest.matchCase(supportCase).value();

        // then
        assertThat(correlationKey).isEqualTo("conversation-4711/UNKNOWN");
    }

    @Test
    void shouldHandleNullSubject() {
        SupportCase supportCase =
                supportCase(
                        null,
                        EmailCommunicationContext.builder()
                                .conversationId("conv-5")
                                .emailAddress("customer@camunda.com")
                                .build());

        String correlationKey = serviceUnderTest.matchCase(supportCase).value();

        assertThat(correlationKey).isEqualTo("customer@camunda.com/UNKNOWN");
    }

    private SupportCase supportCase(String subject, CommunicationContext communicationContext) {
        return SupportCase.builder()
                .subject(subject)
                .request("Need help")
                .communicationContext(communicationContext)
                .build();
    }

    private record MyCommunicationContext(String conversationId)
            implements CustomCommunicationContext {

        @Override
        public String channel() {
            return "mychannel";
        }
    }
}
