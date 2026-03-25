package io.camunda.bizsol.bb.comm_agent.models;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
@With
public record SupportCase(
        String subject,
        String request,
        String correlationKey,
        CommunicationContext communicationContext,
        List<Attachment> attachments,
        LocalDateTime receivedDateTime) {}
