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
import java.util.List;

/**
 * Orchestrates the full TopicAI pipeline for a stored transcript:
 * <ol>
 *   <li>Read the transcript fragment (word-level phrase array) from S3.</li>
 *   <li>POST it to the eligibility MS {@code /feedback-intelligence/transcripts/topic-ai}.</li>
 *   <li>Poll {@code GET .../topic-ai} until the result is ready.</li>
 *   <li>Update the DynamoDB CSAT-score record with {@code topics_detected} and {@code sub_topics_detected}.</li>
 * </ol>
 */
@Service
public class TopicAiPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TopicAiPipelineService.class);

    private static final TypeReference<SendTranscriptRequest> FRAGMENT_TYPE =
            new TypeReference<>() {};

    private final TranscriptS3Reader s3Reader;
    private final TopicAiClient topicAiClient;
    private final CsatScoreRepository csatScoreRepository;

    public TopicAiPipelineService(TranscriptS3Reader s3Reader,
                                  TopicAiClient topicAiClient,
                                  CsatScoreRepository csatScoreRepository) {
        this.s3Reader = s3Reader;
        this.topicAiClient = topicAiClient;
        this.csatScoreRepository = csatScoreRepository;
    }

    /**
     * Fire-and-forget variant: runs the pipeline on a background thread so the
     * transcript-generate endpoint can return immediately.
     */
    @Async
    public void processAsync(String transcriptId, String tenantId, String fragmentS3Uri) {
        process(transcriptId, tenantId, fragmentS3Uri);
    }

    /**
     * Runs the full pipeline for a single stored transcript.
     *
     * @param transcriptId       the UUID of the transcript (DynamoDB sort key)
     * @param tenantId           the tenant id (DynamoDB partition key)
     * @param fragmentS3Uri      the {@code s3://} URI of the transcript-fragment.json file
     * @return a summary of what happened
     */
    public PipelineResult process(String transcriptId, String tenantId, String fragmentS3Uri) {
        log.info("Starting TopicAI pipeline — transcriptId={} tenantId={} fragment={}",
                transcriptId, tenantId, fragmentS3Uri);

        // Step 1: Read the transcript fragment from S3
        SendTranscriptRequest fragment;
        try {
            fragment = s3Reader.read(fragmentS3Uri, FRAGMENT_TYPE);
        } catch (Exception e) {
            log.error("Failed to read fragment from S3 — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "S3 read failed: " + e.getMessage());
        }

        // Ensure the correlationId matches the transcriptId so we can look it up later
        fragment.setCorrelationId(transcriptId);

        // Step 2: POST to the eligibility MS /topic-ai
        SendTranscriptResponse sendResponse;
        try {
            sendResponse = topicAiClient.sendTranscript(fragment);
        } catch (Exception e) {
            log.error("Failed to send transcript to TopicAI — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "TopicAI send failed: " + e.getMessage());
        }

        if (sendResponse.getStatusCode() != 202 && sendResponse.getStatusCode() != 200) {
            log.warn("Unexpected status from TopicAI send — transcriptId={} status={}",
                    transcriptId, sendResponse.getStatusCode());
            return PipelineResult.failure(transcriptId,
                    "Unexpected HTTP status from send: " + sendResponse.getStatusCode());
        }

        // Step 3: Poll for the result
        TopicAiResultResponse result = topicAiClient.pollForResult(transcriptId, tenantId);
        if (result == null) {
            log.warn("TopicAI result timed out — transcriptId={}", transcriptId);
            return PipelineResult.failure(transcriptId, "TopicAI result polling timed out");
        }

        // Step 4: Extract topics and subtopics from the result
        List<String> topics = new ArrayList<>();
        List<String> subTopics = new ArrayList<>();

        if (result.getPayload() != null) {
            TopicAiResultResponse.TopicDetail primary = result.getPayload().getPrimaryTopic();
            if (primary != null) {
                addIfPresent(topics, primary.getName());
                addIfPresent(topics, primary.getTopic());
                addIfPresent(subTopics, primary.getSubTopic());
            }

            List<TopicAiResultResponse.TopicDetail> secondary = result.getPayload().getSecondaryTopics();
            if (secondary != null) {
                for (TopicAiResultResponse.TopicDetail detail : secondary) {
                    addIfPresent(topics, detail.getName());
                    addIfPresent(topics, detail.getTopic());
                    addIfPresent(subTopics, detail.getSubTopic());
                }
            }
        }

        // Deduplicate while preserving order
        topics = deduplicate(topics);
        subTopics = deduplicate(subTopics);

        // Step 5: Update DynamoDB with topics and subtopics
        try {
            csatScoreRepository.updateTopics(tenantId, transcriptId, topics, subTopics);
        } catch (Exception e) {
            log.error("Failed to update DynamoDB topics — transcriptId={}: {}", transcriptId, e.getMessage());
            return PipelineResult.failure(transcriptId, "DynamoDB update failed: " + e.getMessage());
        }

        log.info("TopicAI pipeline complete — transcriptId={} topics={} subTopics={}",
                transcriptId, topics, subTopics);
        return PipelineResult.success(transcriptId, topics, subTopics);
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    private static List<String> deduplicate(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String s : input) {
            if (!result.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    public static class PipelineResult {
        private final String transcriptId;
        private final boolean success;
        private final List<String> topics;
        private final List<String> subTopics;
        private final String error;

        private PipelineResult(String transcriptId, boolean success,
                               List<String> topics, List<String> subTopics, String error) {
            this.transcriptId = transcriptId;
            this.success = success;
            this.topics = topics;
            this.subTopics = subTopics;
            this.error = error;
        }

        public static PipelineResult success(String transcriptId, List<String> topics, List<String> subTopics) {
            return new PipelineResult(transcriptId, true, topics, subTopics, null);
        }

        public static PipelineResult failure(String transcriptId, String error) {
            return new PipelineResult(transcriptId, false, List.of(), List.of(), error);
        }

        public String getTranscriptId() { return transcriptId; }
        public boolean isSuccess() { return success; }
        public List<String> getTopics() { return topics; }
        public List<String> getSubTopics() { return subTopics; }
        public String getError() { return error; }
    }
}
