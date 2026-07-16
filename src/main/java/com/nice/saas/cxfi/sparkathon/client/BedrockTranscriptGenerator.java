package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Generates a sample contact-center chat transcript by invoking Amazon Bedrock.
 * The system + user prompt is loaded from a single resource file; the model id
 * and inference parameters are defined here.
 */
@Component
public class BedrockTranscriptGenerator {

    private static final Logger log = LoggerFactory.getLogger(BedrockTranscriptGenerator.class);

    private static final String PROMPT_RESOURCE = "prompts/transcript-prompt.txt";
    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    /** Bedrock model (or inference profile) id used with the Converse API. */
    private static final String MODEL_ID = "us.anthropic.claude-sonnet-4-5-20250929-v1:0";

    /** Maximum tokens to generate for a transcript. */
    private static final int MAX_TOKENS = 2048;

    /** Sampling temperature. */
    private static final float TEMPERATURE = 0.8f;

    private final BedrockRuntimeClient client;
    private final ObjectMapper objectMapper;

    private final String systemPrompt;
    private final String userPromptTemplate;

    public BedrockTranscriptGenerator(BedrockRuntimeClient client, ObjectMapper objectMapper) {
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
     * Invokes Bedrock and returns the parsed transcript turns.
     *
     * @param topicPhrase       the topic phrase injected into the prompt
     * @param temperamentPhrase the temperament phrase injected into the prompt
     */
    public List<Map<String, String>> generate(String topicPhrase, String temperamentPhrase) {
        String userPrompt = userPromptTemplate
                .replace("{topic}", topicPhrase)
                .replace("{temperament}", temperamentPhrase);

        log.info("Generating transcript via Bedrock model={}", MODEL_ID);

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
        return parseTurns(text);
    }

    private List<Map<String, String>> parseTurns(String modelOutput) {
        String json = extractJsonArray(modelOutput);
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (IOException e) {
            log.error("Failed to parse transcript JSON from model output: {}", modelOutput, e);
            throw new IllegalStateException("Bedrock returned a transcript that was not valid JSON", e);
        }
    }

    /** Trims any stray prose/markdown fences the model may add, keeping the JSON array. */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text.trim();
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load prompt resource: " + path, e);
        }
    }
}
