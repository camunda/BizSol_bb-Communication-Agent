package io.camunda.bizsol.bb.comm_agent.models;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record EmailCommunicationContext(String conversationId, String emailAddress)
        implements CommunicationContext {
    @Override
    public String channel() {
        return "email";
    }
}
