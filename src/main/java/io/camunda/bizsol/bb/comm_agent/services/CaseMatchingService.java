package io.camunda.bizsol.bb.comm_agent.services;

import io.camunda.bizsol.bb.comm_agent.models.CorrelationKey;
import io.camunda.bizsol.bb.comm_agent.models.EmailCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.PhoneCommunicationContext;
import io.camunda.bizsol.bb.comm_agent.models.SupportCase;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves incoming support cases to correlation keys based on contract data, communication
 * context, and inferred customer intent.
 */
@Service
public class CaseMatchingService {

    private static final Logger log = LoggerFactory.getLogger(CaseMatchingService.class);
    private static final Pattern CONTRACT_NUMBER_PATTERN = Pattern.compile("\\bV\\d{9}\\b");
    private static final String UNKNOWN_INTENT = "UNKNOWN";
    private static final Map<String, String> INTENT_PATTERN_MAP = createIntentPatternMap();

    /**
     * Builds a correlation key for the provided support case.
     *
     * @param supportCase incoming support case to correlate
     * @return correlation key composed of source identifier and detected customer intent
     */
    public CorrelationKey matchCase(SupportCase supportCase) {
        final String customerIntent = evaluateCustomerIntent(supportCase.subject());
        final String contractNumber = extractContractNumber(supportCase.subject());

        final String correlationSource;
        if (contractNumber != null) {
            correlationSource = contractNumber;
        } else {
            correlationSource =
                    switch (supportCase.communicationContext()) {
                        case EmailCommunicationContext c -> c.emailAddress();
                        case PhoneCommunicationContext c -> c.phoneNumber();
                        default -> supportCase.communicationContext().conversationId();
                    };
        }

        final String correlationKey = correlationSource + "/" + customerIntent;
        log.info("Match incoming support case to correlation: {}", correlationKey);
        return new CorrelationKey(correlationKey);
    }

    /**
     * Evaluates the customer intent from the given subject using the configured intent patterns.
     *
     * @param subject subject text to inspect; may be {@code null}
     * @return mapped intent, or {@code UNKNOWN_INTENT} if no pattern matches
     */
    private String evaluateCustomerIntent(String subject) {
        if (subject == null) {
            return UNKNOWN_INTENT;
        }
        for (Map.Entry<String, String> entry : INTENT_PATTERN_MAP.entrySet()) {
            if (Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE).matcher(subject).find()) {
                return entry.getValue();
            }
        }
        return UNKNOWN_INTENT;
    }

    /**
     * Extracts the first contract number found in the given subject line. Uses {@code
     * CONTRACT_NUMBER_PATTERN} to search for a match.
     *
     * @param subject the subject text to parse; may be {@code null}
     * @return the first matched contract number, or {@code null} if the subject is {@code null} or
     *     no contract number is found
     */
    private String extractContractNumber(String subject) {
        if (subject == null) {
            return null;
        }
        Matcher matcher = CONTRACT_NUMBER_PATTERN.matcher(subject);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * Creates the configured keyword-to-intent mapping used to detect customer intent from case
     * subjects.
     *
     * @return immutable map of subject keywords and their mapped intent identifiers
     */
    private static Map<String, String> createIntentPatternMap() {
        Map<String, String> intentPatternMap = new LinkedHashMap<>();
        intentPatternMap.put("dog", "DOG_INSURANCE");
        intentPatternMap.put("car", "CAR_INSURANCE");
        return Collections.unmodifiableMap(intentPatternMap);
    }
}
