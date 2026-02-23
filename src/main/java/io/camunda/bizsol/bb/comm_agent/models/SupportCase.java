package io.camunda.bizsol.bb.comm_agent.models;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record SupportCase(
        String subject,
        String request,
        CommunicationContext communicationContext,
        List<Attachment> attachments,
        LocalDateTime receivedDateTime) {}
