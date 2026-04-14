package io.camunda.bizsol.bb.comm_agent.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;

@ExtendWith(MockitoExtension.class)
class SemanticMatchingEvaluatorTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private SemanticMatchingEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SemanticMatchingEvaluator(chatClientBuilder, 0.7F);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    void shouldPutModelReasonIntoFeedback() {
        when(callResponseSpec.content())
                .thenReturn(
                        """
                        {"score":0.82,"reason":"Both sentences express the same cancellation intent."}
                        """);

        EvaluationResponse response =
                evaluator.evaluate(
                        new EvaluationRequest("Cancel my order", "Please cancel the order"));

        assertThat(response.isPass()).isTrue();
        assertThat(response.getScore()).isEqualTo(0.82F);
        assertThat(response.getFeedback())
                .isEqualTo("Both sentences express the same cancellation intent.");
    }

    @Test
    void shouldFallbackToRawModelResponseWhenJsonParsingFails() {
        when(callResponseSpec.content())
                .thenReturn(
                        "Score: 0.65. Reason: The topics overlap, but one message also asks for escalation.");

        EvaluationResponse response =
                evaluator.evaluate(
                        new EvaluationRequest("Need a refund", "Need a refund and manager help"));

        assertThat(response.isPass()).isFalse();
        assertThat(response.getScore()).isEqualTo(0.65F);
        assertThat(response.getFeedback())
                .isEqualTo(
                        "Score: 0.65. Reason: The topics overlap, but one message also asks for escalation.");
    }
}
