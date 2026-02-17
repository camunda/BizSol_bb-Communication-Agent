package io.camunda.bizsol.bb.comm_agent.models;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;

@Builder
@Jacksonized
public record SupportCase(
        String subject,
        String request,
        CommunicationContext communicationContext,
        List<Attachment> attachments,
        LocalDateTime receivedDateTime) {
}
