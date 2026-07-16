package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Bedrock model (or inference profile) id used with the Converse API. */
    private static final String MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    /** Prediction output is compact JSON — plenty of headroom here. */
    private static final int MAX_TOKENS = 512;

    /** Low temperature: we want deterministic classification, not creativity. */
    private static final float TEMPERATURE = 0.2f;

    private final BedrockRuntimeClient client;
    private final ObjectMapper objectMapper;

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

        ConverseResponse response = client.converse(req -> req
                .modelId(MODEL_ID)
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userPrompt))
                        .build())
                .inferenceConfig(cfg -> cfg
                        .maxTokens(MAX_TOKENS)
                        .temperature(TEMPERATURE)));

        String text = response.output().message().content().get(0).text();
        return parsePrediction(text);
    }

    private CsatPrediction parsePrediction(String modelOutput) {
        String json = extractJsonObject(modelOutput);
        try {
            return objectMapper.readValue(json, CsatPrediction.class);
        } catch (IOException e) {
            log.error("Failed to parse CSAT prediction JSON from model output: {}", modelOutput, e);
            throw new IllegalStateException("Bedrock returned a CSAT prediction that was not valid JSON", e);
        }
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
