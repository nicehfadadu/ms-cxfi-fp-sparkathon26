package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nice.saas.cxfi.sparkathon.model.CsatPrediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Predicts a per-interaction CSAT score for a single chat transcript by invoking
 * Amazon Bedrock. The system + user prompt is loaded from a single resource file;
 * the model id and inference parameters are declared here.
 */
@Component
public class BedrockCsatPredictor {

    private static final Logger log = LoggerFactory.getLogger(BedrockCsatPredictor.class);

    private static final String PROMPT_RESOURCE = "prompts/csat-predict-prompt.txt";
    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    /**
     * Bedrock model (inference profile) used with the Converse API. Sonnet is the smallest
     * Claude model that reliably produces valid JSON for multi-sentence string values and
     * whose reasoning-first output actually influences the committed score — Haiku 3
     * frequently drops opening quotes on long values and defaults to score 3 even when
     * its own reasoning contradicts it.
     */
    private static final String MODEL_ID = "us.anthropic.claude-sonnet-4-5-20250929-v1:0";

    /**
     * Prediction output includes chain-of-reasoning + verbatim key phrases + next-best-action
     * on top of the classification fields, so give it comfortable headroom.
     */
    private static final int MAX_TOKENS = 1024;

    /** Low temperature: we want deterministic classification, not creativity. */
    private static final float TEMPERATURE = 0.2f;

    private final BedrockRuntimeClient client;
    private final ObjectMapper objectMapper;
    /** Lenient reader used only for parsing model output — accepts single quotes and unescaped control chars. */
    private final ObjectMapper lenientMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private final String systemPrompt;
    private final String userPromptTemplate;

    public BedrockCsatPredictor(BedrockRuntimeClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;

        String raw = readResource(PROMPT_RESOURCE);
        int userIdx = raw.indexOf(USER_MARKER);
        if (!raw.contains(SYSTEM_MARKER) || userIdx < 0) {
            throw new IllegalStateException(
                    PROMPT_RESOURCE + " must contain both " + SYSTEM_MARKER + " and " + USER_MARKER + " markers");
        }
        this.systemPrompt = raw.substring(raw.indexOf(SYSTEM_MARKER) + SYSTEM_MARKER.length(), userIdx).trim();
        this.userPromptTemplate = raw.substring(userIdx + USER_MARKER.length()).trim();
    }

    /**
     * Invokes Bedrock and returns the parsed CSAT prediction for a single chat.
     *
     * @param transcript      ordered turns, each a single-key map keyed by "agent"/"customer"
     * @param topic           optional topic hint (may be null/blank)
     * @param allowedTopics   optional list of topic phrases the model may pick from; when
     *                        non-empty, the model must restrict topicsDetected to this set
     */
    public CsatPrediction predict(List<Map<String, String>> transcript,
                                  String topic,
                                  List<String> allowedTopics) {
        String userPrompt = userPromptTemplate
                .replace("{transcript}", toJson(transcript))
                .replace("{topic}", topic == null ? "" : topic)
                .replace("{allowedTopics}", allowedTopics == null || allowedTopics.isEmpty()
                        ? "" : toJson(allowedTopics));

        log.info("Predicting CSAT via Bedrock model={} turns={}", MODEL_ID, transcript.size());

        String firstText = converseOnce(userPrompt, null, null);
        try {
            return parsePrediction(firstText);
        } catch (IOException initial) {
            // Long multi-sentence string values sometimes come back with a missing opening
            // quote. Ask the model to resend as strict JSON, feeding the malformed output
            // back so it can self-correct. One retry only.
            log.warn("Initial CSAT prediction JSON did not parse — asking model to resend as strict JSON");
            String retryText = converseOnce(userPrompt, firstText,
                    "Your previous response was not valid JSON. Resend the SAME object as strict JSON — "
                            + "every string value MUST be wrapped in double quotes, no unquoted values, "
                            + "no prose, no markdown fences. Only the JSON object.");
            try {
                return parsePrediction(retryText);
            } catch (IOException retry) {
                log.error("Failed to parse CSAT prediction JSON after retry. Original output: {}\nRetry output: {}",
                        firstText, retryText, retry);
                throw new IllegalStateException("Bedrock returned a CSAT prediction that was not valid JSON", retry);
            }
        }
    }

    /**
     * One round-trip to Bedrock. If {@code priorAssistantText} is non-null the call includes
     * the model's previous reply plus a corrective follow-up user message ({@code followupUser}).
     */
    private String converseOnce(String userPrompt, String priorAssistantText, String followupUser) {
        ConverseResponse response = client.converse(req -> {
            req.modelId(MODEL_ID)
                    .system(SystemContentBlock.fromText(systemPrompt))
                    .inferenceConfig(cfg -> cfg
                            .maxTokens(MAX_TOKENS)
                            .temperature(TEMPERATURE));
            if (priorAssistantText == null) {
                req.messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userPrompt))
                        .build());
            } else {
                req.messages(
                        Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.fromText(userPrompt))
                                .build(),
                        Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(ContentBlock.fromText(priorAssistantText))
                                .build(),
                        Message.builder()
                                .role(ConversationRole.USER)
                                .content(ContentBlock.fromText(followupUser))
                                .build());
            }
        });
        return response.output().message().content().get(0).text();
    }

    /**
     * Parses the model's output into a {@link CsatPrediction}. Uses a lenient JSON reader
     * that tolerates single quotes and unescaped control chars — malformed enough to fail
     * that still triggers a retry from {@link #predict}.
     */
    private CsatPrediction parsePrediction(String modelOutput) throws IOException {
        String json = extractJsonObject(modelOutput);
        return lenientMapper.readValue(json, CsatPrediction.class);
    }

    /** Trims any stray prose/markdown fences, keeping the outermost JSON object. */
    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize prompt input to JSON", e);
        }
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load prompt resource: " + path, e);
        }
    }
}
