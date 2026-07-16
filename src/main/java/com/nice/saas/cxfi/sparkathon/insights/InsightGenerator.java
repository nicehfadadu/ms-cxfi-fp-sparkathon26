package com.nice.saas.cxfi.sparkathon.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.saas.cxfi.sparkathon.model.RecommendedAction;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single LLM step: turns the deterministic per-topic aggregates plus sampled
 * transcript excerpts into prioritized actions via Amazon Bedrock. Output topics are
 * validated against the allowed set so the model can never introduce a new topic.
 */
@Component
public class InsightGenerator {

    private static final Logger log = LoggerFactory.getLogger(InsightGenerator.class);

    private static final String PROMPT_RESOURCE = "prompts/insights-prompt.txt";
    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    private static final String MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";
    private static final int MAX_TOKENS = 2048;
    private static final float TEMPERATURE = 0.3f;

    private final BedrockRuntimeClient client;
    private final TranscriptSampler sampler;
    private final ObjectMapper objectMapper;

    private final String systemPrompt;
    private final String userPromptTemplate;

    public InsightGenerator(BedrockRuntimeClient client, TranscriptSampler sampler, ObjectMapper objectMapper) {
        this.client = client;
        this.sampler = sampler;
        this.objectMapper = objectMapper;

        String raw = readResource(PROMPT_RESOURCE);
        int userIdx = raw.indexOf(USER_MARKER);
        if (!raw.contains(SYSTEM_MARKER) || userIdx < 0) {
            throw new IllegalStateException(PROMPT_RESOURCE + " must contain both markers");
        }
        this.systemPrompt = raw.substring(raw.indexOf(SYSTEM_MARKER) + SYSTEM_MARKER.length(), userIdx).trim();
        this.userPromptTemplate = raw.substring(userIdx + USER_MARKER.length()).trim();
    }

    /** Builds prioritized actions for the ranked topics in the aggregation. */
    public List<RecommendedAction> generate(Aggregation agg) {
        if (agg.getTopics().isEmpty()) {
            return List.of();
        }

        String topicBlocks = buildTopicBlocks(agg);
        String userPrompt = userPromptTemplate
                .replace("{avgPredictedCsat}", String.valueOf(agg.getAvgPredictedCsat()))
                .replace("{predictedScored}", String.valueOf(agg.getPredictedScored()))
                .replace("{allowedTopics}", String.join(", ", agg.getAllowedTopics()))
                .replace("{topicBlocks}", topicBlocks);

        log.info("Generating insights via Bedrock model={} for {} topics", MODEL_ID, agg.getTopics().size());

        ConverseResponse response = client.converse(req -> req
                .modelId(MODEL_ID)
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userPrompt))
                        .build())
                .inferenceConfig(cfg -> cfg.maxTokens(MAX_TOKENS).temperature(TEMPERATURE)));

        String text = response.output().message().content().get(0).text();
        return parseActions(text, agg);
    }

    /** Renders each topic's numbers + low/high transcript excerpts for the prompt. */
    private String buildTopicBlocks(Aggregation agg) {
        StringBuilder sb = new StringBuilder();
        for (TopicAggregate t : agg.getTopics()) {
            List<String> low = sampler.excerpts(t.getLowSamplePaths());
            List<String> high = sampler.excerpts(t.getHighSamplePaths());
            sb.append("### topic: ").append(t.getTopic())
              .append(" | predictedCsat=").append(t.getAvgPredictedCsat())
              .append(" | interactions=").append(t.getCount()).append('\n');
            sb.append("LOW-scoring samples:\n").append(joinSamples(low)).append('\n');
            sb.append("HIGH-scoring samples:\n").append(joinSamples(high)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String joinSamples(List<String> samples) {
        if (samples.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            sb.append("- sample ").append(i + 1).append(":\n").append(samples.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    /** Parses the model's JSON, keeps only allowed topics, and fills in the code-computed numbers. */
    private List<RecommendedAction> parseActions(String modelOutput, Aggregation agg) {
        String json = extractJsonArray(modelOutput);
        List<RecommendedAction> actions = new ArrayList<>();
        Set<String> allowed = new HashSet<>(agg.getAllowedTopics());
        Set<String> seen = new HashSet<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            int rank = 1;
            for (JsonNode node : arr) {
                String topic = node.path("topic").asText(null);
                if (topic == null || !allowed.contains(topic) || !seen.add(topic)) {
                    continue; // drop invented/duplicate topics
                }
                TopicAggregate agtopic = find(agg, topic);
                if (agtopic == null) {
                    continue;
                }
                RecommendedAction a = new RecommendedAction();
                a.setRank(rank++);
                a.setTopic(topic);
                a.setPriority(normalizePriority(node.path("priority").asText("medium")));
                a.setWhat(node.path("what").asText(""));
                a.setWhy(node.path("why").asText(""));
                a.setPredictedCsat(agtopic.getAvgPredictedCsat());
                a.setChats(agtopic.getCount());
                actions.add(a);
            }
        } catch (IOException e) {
            log.error("Failed to parse insights JSON from model output: {}", modelOutput, e);
            throw new IllegalStateException("Bedrock returned insights that were not valid JSON", e);
        }
        return actions;
    }

    private static TopicAggregate find(Aggregation agg, String topic) {
        return agg.getTopics().stream().filter(t -> t.getTopic().equals(topic)).findFirst().orElse(null);
    }

    private static String normalizePriority(String p) {
        String v = p == null ? "" : p.trim().toLowerCase();
        return (v.equals("high") || v.equals("medium") || v.equals("low")) ? v : "medium";
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text.trim();
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load prompt resource: " + path, e);
        }
    }
}
