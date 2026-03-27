package io.camunda.bizsol.bb.comm_agent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

public class SemanticMatchingEvaluator implements Evaluator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SCORE_PATTERN =
            Pattern.compile("(?<!\\d)(?:0(?:\\.\\d+)?|1(?:\\.0+)?)(?!\\d)");
    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE =
            new PromptTemplate(
                    """
    Task: Evaluate the semantic similarity between two sentences, A and B.
    Assign a similarity score between 0.0 and 1.0, where:
    1.0 = The sentences are semantically identical (same meaning, even if phrased differently).
    0.0 = The sentences are completely unrelated in meaning.
    0.1–0.9 = Partial semantic similarity, with higher values indicating greater similarity.
    Consider the overall intent, meaning, and context of each sentence rather than exact wording.
    Respond with JSON only.
    Include a numeric "score" field between 0.0 and 1.0.
    Include a short "reason" field explaining the score.
    Do not include markdown fences or extra text outside the JSON object.

    A:
    {a}

    B:
    {b}
    """);

    private final ChatClient.Builder chatClientBuilder;
    private final float threshold;

    public SemanticMatchingEvaluator(ChatClient.Builder chatClientBuilder, float threshold) {
        this.chatClientBuilder = chatClientBuilder;
        this.threshold = threshold;
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
        String expected = evaluationRequest.getResponseContent();
        String userMessage =
                DEFAULT_PROMPT_TEMPLATE.render(
                        Map.of("a", evaluationRequest.getUserText(), "b", expected));
        String evaluationResponse =
                this.chatClientBuilder.build().prompt().user(userMessage).call().content();
        ParsedEvaluation parsedEvaluation = parseEvaluation(evaluationResponse);
        return new EvaluationResponse(
                parsedEvaluation.score() > threshold,
                parsedEvaluation.score(),
                parsedEvaluation.feedback(),
                Collections.emptyMap());
    }

    private ParsedEvaluation parseEvaluation(String evaluationResponse) {
        if (evaluationResponse == null || evaluationResponse.isBlank()) {
            return new ParsedEvaluation(0.0F, "");
        }

        String normalizedResponse = unwrapCodeFence(evaluationResponse);
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(normalizedResponse);
            float score = parseScore(jsonNode.path("score").asText());
            String feedback = jsonNode.path("reason").asText("").trim();
            if (feedback.isEmpty()) {
                feedback = normalizedResponse;
            }
            return new ParsedEvaluation(score, feedback);
        } catch (Exception ignored) {
            return new ParsedEvaluation(parseScore(normalizedResponse), normalizedResponse);
        }
    }

    private float parseScore(String value) {
        if (value == null || value.isBlank()) {
            return 0.0F;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            Matcher matcher = SCORE_PATTERN.matcher(value);
            if (matcher.find()) {
                return Float.parseFloat(matcher.group());
            }
            return 0.0F;
        }
    }

    private String unwrapCodeFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewLine < 0 || lastFence <= firstNewLine) {
            return trimmed;
        }
        return trimmed.substring(firstNewLine + 1, lastFence).trim();
    }

    private record ParsedEvaluation(float score, String feedback) {}
}
