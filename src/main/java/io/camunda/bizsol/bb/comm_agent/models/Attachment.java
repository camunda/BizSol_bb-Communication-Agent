package io.camunda.bizsol.bb.comm_agent.models;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record Attachment(String documentId, String storeId, String contentHash, Metadata metadata) {
    @Builder
    @Jacksonized
    public record Metadata(String fileName, String contentType, Long size) {}
}
