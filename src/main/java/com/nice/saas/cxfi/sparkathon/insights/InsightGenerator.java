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
 * The LLM step: turns the deterministic per-topic aggregates plus sampled transcript
 * excerpts into prioritized actions via Amazon Bedrock. Each segment gets its own
 * Bedrock call with its own prompt — respondents are analysed on actual-vs-predicted,
 * non-respondents on predicted alone. Output topics are validated against the allowed
 * set so the model can never introduce a new topic.
 */
@Component
public class InsightGenerator {

    private static final Logger log = LoggerFactory.getLogger(InsightGenerator.class);

    private static final String NON_RESPONDENT_PROMPT = "prompts/insights-prompt.txt";
    private static final String RESPONDENT_PROMPT = "prompts/insights-respondent-prompt.txt";
    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    // Claude 3 Sonnet — invoked on-demand directly (no cross-region inference profile).
    private static final String MODEL_ID = "anthropic.claude-3-sonnet-20240229-v1:0";
    // 4096 is the model's maximum output tokens; requesting more is rejected by Bedrock.
    private static final int MAX_TOKENS = 4096;
    private static final float TEMPERATURE = 0.3f;

    private final BedrockRuntimeClient client;
    private final TranscriptSampler sampler;
    private final ObjectMapper objectMapper;

    private final Prompt nonRespondentPrompt;
    private final Prompt respondentPrompt;

    public InsightGenerator(BedrockRuntimeClient client, TranscriptSampler sampler, ObjectMapper objectMapper) {
        this.client = client;
        this.sampler = sampler;
        this.objectMapper = objectMapper;
        this.nonRespondentPrompt = loadPrompt(NON_RESPONDENT_PROMPT);
        this.respondentPrompt = loadPrompt(RESPONDENT_PROMPT);
    }

    /** Predicted-only analysis for interactions with no survey response. */
    public List<RecommendedAction> generateNonRespondent(Aggregation agg) {
        if (agg == null || agg.getTopics().isEmpty()) {
            return List.of();
        }
        String userPrompt = nonRespondentPrompt.user
                .replace("{avgPredictedCsat}", String.valueOf(agg.getAvgPredictedCsat()))
                .replace("{predictedScored}", String.valueOf(agg.getPredictedScored()))
                .replace("{allowedTopics}", String.join(", ", agg.getAllowedTopics()))
                .replace("{topicBlocks}", buildTopicBlocks(agg, false));
        String text = converse(nonRespondentPrompt.system, userPrompt, "non-respondent", agg.getTopics().size());
        return parseActions(text, agg, false);
    }

    /** Actual-vs-predicted analysis for interactions where a survey score came back. */
    public List<RecommendedAction> generateRespondent(Aggregation agg) {
        if (agg == null || agg.getTopics().isEmpty()) {
            return List.of();
        }
        String userPrompt = respondentPrompt.user
                .replace("{avgPredictedCsat}", String.valueOf(agg.getAvgPredictedCsat()))
                .replace("{avgActualCsat}", String.valueOf(agg.getAvgActualCsat()))
                .replace("{predictedScored}", String.valueOf(agg.getPredictedScored()))
                .replace("{allowedTopics}", String.join(", ", agg.getAllowedTopics()))
                .replace("{topicBlocks}", buildTopicBlocks(agg, true));
        String text = converse(respondentPrompt.system, userPrompt, "respondent", agg.getTopics().size());
        return parseActions(text, agg, true);
    }

    private String converse(String system, String userPrompt, String segment, int topicCount) {
        log.info("Generating {} insights via Bedrock model={} for {} topics", segment, MODEL_ID, topicCount);
        ConverseResponse response = client.converse(req -> req
                .modelId(MODEL_ID)
                .system(SystemContentBlock.fromText(system))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userPrompt))
                        .build())
                .inferenceConfig(cfg -> cfg.maxTokens(MAX_TOKENS).temperature(TEMPERATURE)));
        return response.output().message().content().get(0).text();
    }

    /** Renders each topic's numbers + contrastive transcript excerpts for the prompt. */
    private String buildTopicBlocks(Aggregation agg, boolean respondent) {
        StringBuilder sb = new StringBuilder();
        for (TopicAggregate t : agg.getTopics()) {
            sb.append("### topic: ").append(t.getTopic())
              .append(" | avgPredictedCsat=").append(t.getAvgPredictedCsat());
            if (respondent) {
                sb.append(" | avgActualCsat=").append(t.getAvgActualCsat())
                  .append(" | avgResidual=").append(t.getAvgResidual())
                  .append(" | calibration=").append(t.getDirection());
            }
            sb.append(" | interactions=").append(t.getCount())
              .append(" | scored=").append(t.getScored()).append('\n');

            if (respondent) {
                sb.append("OVER-predicted interactions (actual far BELOW predicted):\n")
                  .append(renderSamples(t.getLowSamples(), true)).append('\n');
                sb.append("UNDER-predicted interactions (actual far ABOVE predicted):\n")
                  .append(renderSamples(t.getHighSamples(), true)).append("\n\n");
            } else {
                sb.append("LOW-scoring interactions (what is going wrong):\n")
                  .append(renderSamples(t.getLowSamples(), false)).append('\n');
                sb.append("HIGH-scoring interactions (what is going right):\n")
                  .append(renderSamples(t.getHighSamples(), false)).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /** Fetches each sample's transcript and prefixes it with its scores. */
    private String renderSamples(List<TranscriptSample> samples, boolean respondent) {
        if (samples == null || samples.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (TranscriptSample s : samples) {
            List<String> ex = sampler.excerpts(List.of(s.getS3Path()));
            String body = ex.isEmpty() ? "(transcript unavailable)" : ex.get(0);
            sb.append("- sample ").append(i++).append(" (predictedCsat=").append(s.getPredictedCsat());
            if (respondent) {
                sb.append(", actualCsat=").append(s.getActualCsat())
                  .append(", residual=").append(s.residual());
            }
            sb.append("):\n").append(body).append('\n');
        }
        return sb.toString().trim();
    }

    /** Parses the model's JSON, keeps only allowed topics, and fills in the code-computed numbers. */
    private List<RecommendedAction> parseActions(String modelOutput, Aggregation agg, boolean respondent) {
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
                a.setGap(node.path("gap").asText(""));
                a.setWhat(node.path("what").asText(""));
                a.setWhy(node.path("why").asText(""));
                a.setPredictedCsat(agtopic.getAvgPredictedCsat());
                a.setChats(agtopic.getCount());
                if (respondent) {
                    a.setActualCsat(agtopic.getAvgActualCsat());
                    a.setResidual(agtopic.getAvgResidual());
                    a.setDirection(agtopic.getDirection());
                    a.setReason(node.path("reason").asText(""));
                }
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

    private Prompt loadPrompt(String path) {
        String raw = readResource(path);
        int userIdx = raw.indexOf(USER_MARKER);
        if (!raw.contains(SYSTEM_MARKER) || userIdx < 0) {
            throw new IllegalStateException(path + " must contain both markers");
        }
        String system = raw.substring(raw.indexOf(SYSTEM_MARKER) + SYSTEM_MARKER.length(), userIdx).trim();
        String user = raw.substring(userIdx + USER_MARKER.length()).trim();
        return new Prompt(system, user);
    }

    private String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load prompt resource: " + path, e);
        }
    }

    /** A parsed system/user prompt pair. */
    private static final class Prompt {
        final String system;
        final String user;

        Prompt(String system, String user) {
            this.system = system;
            this.user = user;
        }
    }
}
