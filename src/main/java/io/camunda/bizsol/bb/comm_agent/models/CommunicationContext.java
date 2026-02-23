package io.camunda.bizsol.bb.comm_agent.models;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "channel")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EmailCommunicationContext.class, name = "email"),
    @JsonSubTypes.Type(value = PhoneCommunicationContext.class, name = "phone")
})
public sealed interface CommunicationContext
        permits EmailCommunicationContext, PhoneCommunicationContext, CustomCommunicationContext {
    String channel();

    String conversationId();
}
