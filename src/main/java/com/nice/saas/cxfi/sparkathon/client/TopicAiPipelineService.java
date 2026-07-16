package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptResponse;
import com.nice.saas.cxfi.sparkathon.model.TopicAiResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Orchestrates the full TopicAI pipeline for a stored transcript:
 * <ol>
 *   <li>Read the transcript fragment (word-level phrase array) from S3.</li>
 *   <li>POST it to the eligibility MS
 *       {@code POST /feedback-intelligence/transcripts/topic-ai}.</li>
 *   <li>Poll
 *       {@code GET /feedback-intelligence/transcripts/{correlationId}/topic-ai?tenantId=...}
 *       until the result is ready (mirrors {@code get_topicai_result()} in simulate.py and
 *       {@code TopicAiController.getTopicAiResult()} in ms-cxfi-eligibility-engine).</li>
 *   <li>Extract {@code topics_detected} and {@code sub_topics_detected} from the payload:
 *       <ul>
 *         <li>topics = {@code name} field from primaryTopic + secondaryTopics (mirrors how
 *             results.py reads {@code pt.get("name") or pt.get("subTopic")})</li>
 *         <li>subtopics = {@code subTopic} field from all TopicDetail entries</li>
 *       </ul>
 *   </li>
 *   <li>Compute a predicted CSAT score via {@link CsatScorePredictor}.</li>
 *   <li>Write topics, subtopics and predicted CSAT back to DynamoDB in one
 *       {@code UpdateItem}.</li>
 * </ol>
 */
@Service
public class TopicAiPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TopicAiPipelineService.class);

    private static final TypeReference<SendTranscriptRequest> FRAGMENT_TYPE = new TypeReference<>() {};

    private final TranscriptS3Reader s3Reader;
    private final TopicAiClient topicAiClient;
    private final CsatScoreRepository csatScoreRepository;
    private final CsatScorePredictor csatScorePredictor;

    public TopicAiPipelineService(TranscriptS3Reader s3Reader,
                                  TopicAiClient topicAiClient,
                                  CsatScoreRepository csatScoreRepository,
                                  CsatScorePredictor csatScorePredictor) {
        this.s3Reader = s3Reader;
        this.topicAiClient = topicAiClient;
        this.csatScoreRepository = csatScoreRepository;
        this.csatScorePredictor = csatScorePredictor;
    }

    /** Fire-and-forget: runs the pipeline on a Spring async thread. */
    @Async
    public void processAsync(String transcriptId, String tenantId, String fragmentS3Uri) {
        process(transcriptId, tenantId, fragmentS3Uri);
    }

    /**
     * Synchronous pipeline. Returns a {@link PipelineResult} describing success or failure.
     */
    public PipelineResult process(String transcriptId, String tenantId, String fragmentS3Uri) {
        log.info("TopicAI pipeline start — transcriptId={} tenantId={}", transcriptId, tenantId);

        // Step 1: Read transcript-fragment.json from S3
        SendTranscriptRequest fragment;
        try {
            fragment = s3Reader.read(fragmentS3Uri, FRAGMENT_TYPE);
        } catch (Exception e) {
            log.error("S3 read failed — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "S3 read failed: " + e.getMessage());
        }

        // Force correlationId = transcriptId so the GET result lookup matches
        fragment.setCorrelationId(transcriptId);

        // Step 2: POST to eligibility MS — POST /feedback-intelligence/transcripts/topic-ai
        SendTranscriptResponse sendResponse;
        try {
            sendResponse = topicAiClient.sendTranscript(fragment);
        } catch (Exception e) {
            log.error("TopicAI send failed — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "TopicAI send failed: " + e.getMessage());
        }

        int status = sendResponse.getStatusCode();
        if (status != 202 && status != 200) {
            log.warn("Unexpected send status={} — transcriptId={}", status, transcriptId);
            return PipelineResult.failure(transcriptId, "Unexpected HTTP status from send: " + status);
        }
        log.info("TopicAI accepted — transcriptId={} correlationId={}", transcriptId, sendResponse.getCorrelationId());

        // Step 3: Poll GET /{correlationId}/topic-ai?tenantId=... (mirrors simulate.py get_topicai_result)
        TopicAiResultResponse result = topicAiClient.pollForResult(transcriptId, tenantId);
        if (result == null) {
            log.warn("TopicAI result timed out — transcriptId={}", transcriptId);
            return PipelineResult.failure(transcriptId, "TopicAI result polling timed out");
        }

        // Check httpResponseCode inside the payload envelope (eligibility MS wraps the AH result)
        Integer httpCode = result.getHttpResponseCode();
        if (httpCode != null && httpCode != 200) {
            log.warn("TopicAI processing error httpCode={} — transcriptId={}", httpCode, transcriptId);
            return PipelineResult.failure(transcriptId, "TopicAI processing returned HTTP " + httpCode);
        }

        // Step 4: Extract topics and subtopics
        // Mirrors results.py: pt.get("name") or pt.get("subTopic") for the topic label
        List<String> topics = extractTopics(result);
        List<String> subTopics = extractSubTopics(result);

        // Step 5: Compute predicted CSAT
        Double predictedCsat = csatScorePredictor.predict(result);

        log.info("TopicAI result parsed — transcriptId={} topics={} subTopics={} predictedCsat={}",
                transcriptId, topics, subTopics, predictedCsat);

        // Step 6: Write to DynamoDB in a single UpdateItem
        try {
            csatScoreRepository.updateTopics(tenantId, transcriptId, topics, subTopics, predictedCsat);
        } catch (Exception e) {
            log.error("DynamoDB update failed — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "DynamoDB update failed: " + e.getMessage());
        }

        log.info("TopicAI pipeline complete — transcriptId={}", transcriptId);
        return PipelineResult.success(transcriptId, topics, subTopics, predictedCsat);
    }

    /**
     * Extracts deduplicated topic labels from the payload.
     * For each TopicDetail uses {@code name} falling back to {@code topic} — matching the
     * Python webapp: {@code pt.get("name") or pt.get("subTopic") or ""}.
     */
    private static List<String> extractTopics(TopicAiResultResponse result) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        TopicAiResultResponse.Payload payload = result.getPayload();
        if (payload == null) return List.of();

        addTopicLabel(set, payload.getPrimaryTopic());
        List<TopicAiResultResponse.TopicDetail> secondary = payload.getSecondaryTopics();
        if (secondary != null) {
            secondary.forEach(d -> addTopicLabel(set, d));
        }
        return new ArrayList<>(set);
    }

    private static void addTopicLabel(LinkedHashSet<String> set, TopicAiResultResponse.TopicDetail d) {
        if (d == null) return;
        // Prefer name; fall back to topic field; mirrors results.py logic
        String label = firstNonBlank(d.getName(), d.getTopic(), d.getSubTopic());
        if (label != null) set.add(label);
    }

    /**
     * Extracts deduplicated subTopic values from primary and all secondary topics.
     */
    private static List<String> extractSubTopics(TopicAiResultResponse result) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        TopicAiResultResponse.Payload payload = result.getPayload();
        if (payload == null) return List.of();

        addSubTopic(set, payload.getPrimaryTopic());
        List<TopicAiResultResponse.TopicDetail> secondary = payload.getSecondaryTopics();
        if (secondary != null) {
            secondary.forEach(d -> addSubTopic(set, d));
        }
        return new ArrayList<>(set);
    }

    private static void addSubTopic(LinkedHashSet<String> set, TopicAiResultResponse.TopicDetail d) {
        if (d == null || d.getSubTopic() == null || d.getSubTopic().isBlank()) return;
        set.add(d.getSubTopic());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Result DTO
    // -------------------------------------------------------------------------

    public static class PipelineResult {
        private final String transcriptId;
        private final boolean success;
        private final List<String> topics;
        private final List<String> subTopics;
        private final Double predictedCsatScore;
        private final String error;

        private PipelineResult(String transcriptId, boolean success,
                               List<String> topics, List<String> subTopics,
                               Double predictedCsatScore, String error) {
            this.transcriptId = transcriptId;
            this.success = success;
            this.topics = topics;
            this.subTopics = subTopics;
            this.predictedCsatScore = predictedCsatScore;
            this.error = error;
        }

        public static PipelineResult success(String transcriptId, List<String> topics,
                                             List<String> subTopics, Double predictedCsatScore) {
            return new PipelineResult(transcriptId, true, topics, subTopics, predictedCsatScore, null);
        }

        public static PipelineResult failure(String transcriptId, String error) {
            return new PipelineResult(transcriptId, false, List.of(), List.of(), null, error);
        }

        public String getTranscriptId()       { return transcriptId; }
        public boolean isSuccess()            { return success; }
        public List<String> getTopics()       { return topics; }
        public List<String> getSubTopics()    { return subTopics; }
        public Double getPredictedCsatScore() { return predictedCsatScore; }
        public String getError()              { return error; }
    }
}
