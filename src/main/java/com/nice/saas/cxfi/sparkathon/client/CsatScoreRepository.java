package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.saas.cxfi.sparkathon.model.CsatPrediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists a CSAT-score record to the {@code d6866-feedback-csat-scores} DynamoDB table.
 *
 * <p>Partition key {@code tenantId}, sort key {@code uuid}. The CSAT scores and detected
 * topics are populated by the downstream scoring step, so they are optional here; on
 * transcript creation only the S3 paths are known.
 */
@Component
public class CsatScoreRepository {

    private static final Logger log = LoggerFactory.getLogger(CsatScoreRepository.class);

    private static final String TABLE_NAME = "d6866-feedback-csat-scores";

    /** Every generated transcript is recorded under this fixed survey campaign. */
    private static final String CAMPAIGN = "Post Chat CSAT Survey";

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;

    public CsatScoreRepository(DynamoDbClient dynamoDbClient, ObjectMapper objectMapper) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes a new entry for a freshly generated transcript.
     *
     * @param tenantId               partition key
     * @param uuid                   sort key (the transcript id)
     * @param transcriptS3Path       {@code s3://} URI of the stored transcript
     * @param fragmentS3Path         {@code s3://} URI of the stored topic-ai fragment
     * @param actualCsatScore        actual CSAT score, or {@code null} if not yet known
     * @param predictedCsatScore     predicted CSAT score, or {@code null} if not yet known
     * @param topicsDetected         detected topics, or {@code null}/empty if not yet known
     */
    public void save(String tenantId,
                     String uuid,
                     String transcriptS3Path,
                     String fragmentS3Path,
                     Double actualCsatScore,
                     Double predictedCsatScore,
                     List<String> topicsDetected) {

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("tenantId", AttributeValue.fromS(tenantId));
        item.put("uuid", AttributeValue.fromS(uuid));
        item.put("transcript_s3_path", AttributeValue.fromS(transcriptS3Path));
        item.put("transcript_fragment_s3_path", AttributeValue.fromS(fragmentS3Path));
        item.put("created_at", AttributeValue.fromS(Instant.now().toString()));
        item.put("campaign", AttributeValue.fromS(CAMPAIGN));

        if (actualCsatScore != null) {
            item.put("actual_csat_score", AttributeValue.fromN(actualCsatScore.toString()));
        }
        if (predictedCsatScore != null) {
            item.put("predicted_csat_score", AttributeValue.fromN(predictedCsatScore.toString()));
        }
        if (topicsDetected != null && !topicsDetected.isEmpty()) {
            item.put("topics_detected", AttributeValue.fromSs(topicsDetected));
        }

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build());

        log.info("Stored CSAT-score record tenantId={} uuid={}", tenantId, uuid);
    }

    /**
     * Updates an existing DynamoDB record with detected topics, subtopics, the full raw
     * TopicAI response, and the full LLM CSAT prediction — all in a single {@code UpdateItem}.
     *
     * <p>The top-level {@code predicted_csat_score} column is set from
     * {@link CsatPrediction#getPredictedCsat()} (kept as a plain number for aggregation).
     * The remaining LLM fields — confidence, sentimentTrajectory, resolutionStatus,
     * repeat-contact and churn risks, drivers, rationale, topicsDetected, plus a
     * {@code predictedAt} timestamp — are packed into a DynamoDB Map column named
     * {@code prediction_metadata}. The raw TopicAI response is serialized to JSON and
     * stored as the {@code topicai_response} string attribute.
     *
     * @param tenantId        partition key
     * @param uuid            sort key (transcript id)
     * @param topics          topic names from primaryTopic + secondaryTopics (stored as SS)
     * @param subTopics       subTopic values from the TopicAI result (stored as SS, may be empty)
     * @param prediction      full LLM CSAT prediction, or {@code null} if the LLM call failed
     * @param topicAiResponse the complete TopicAI response object; serialized to JSON and
     *                        stored as the {@code topicai_response} string attribute
     */
    public void updateTopics(String tenantId, String uuid,
                             List<String> topics, List<String> subTopics,
                             CsatPrediction prediction, Object topicAiResponse) {
        Map<String, AttributeValue> key = Map.of(
                "tenantId", AttributeValue.fromS(tenantId),
                "uuid", AttributeValue.fromS(uuid)
        );

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        List<String> setClauses = new ArrayList<>();

        if (topics != null && !topics.isEmpty()) {
            expressionValues.put(":topics", AttributeValue.fromSs(topics));
            setClauses.add("topics_detected = :topics");
        }

        if (subTopics != null && !subTopics.isEmpty()) {
            expressionValues.put(":subtopics", AttributeValue.fromSs(subTopics));
            setClauses.add("sub_topics_detected = :subtopics");
        }

        if (prediction
                != null && prediction.getPredictedCsat() != null) {
            expressionValues.put(":predicted", AttributeValue.fromN(
                    String.format("%.2f", prediction.getPredictedCsat().doubleValue())));
            setClauses.add("predicted_csat_score = :predicted");
        }

        if (prediction != null) {
            expressionValues.put(":metadata", predictionMetadata(prediction));
            setClauses.add("prediction_metadata = :metadata");
        }

        if (topicAiResponse != null) {
            try {
                String json = objectMapper.writeValueAsString(topicAiResponse);
                expressionValues.put(":topicai", AttributeValue.fromS(json));
                setClauses.add("topicai_response = :topicai");
            } catch (JsonProcessingException e) {
                log.warn("Could not serialize topicAiResponse for tenantId={} uuid={}: {}",
                        tenantId, uuid, e.getMessage());
            }
        }

        if (setClauses.isEmpty()) {
            log.info("Nothing to update for tenantId={} uuid={}", tenantId, uuid);
            return;
        }

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET " + String.join(", ", setClauses))
                .expressionAttributeValues(expressionValues)
                .build());

        log.info("Updated TopicAI results for tenantId={} uuid={} topics={} subTopics={} predictedCsat={}",
                tenantId, uuid, topics, subTopics,
                prediction == null ? null : prediction.getPredictedCsat());
    }

    /**
     * Updates the {@code actual_csat_score} attribute on an existing record.
     */
    public void updateActualCsatScore(String tenantId, String uuid, Double score) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "tenantId", AttributeValue.fromS(tenantId),
                        "uuid", AttributeValue.fromS(uuid)
                ))
                .updateExpression("SET actual_csat_score = :score")
                .expressionAttributeValues(Map.of(
                        ":score", AttributeValue.fromN(score.toString())
                ))
                .build());

        log.info("Updated actual_csat_score={} for tenantId={} uuid={}", score, tenantId, uuid);
    }

    /**
     * Fetches a single record by its primary key.
     *
     * @throws IllegalArgumentException if no item exists for the given tenantId + uuid
     */
    public Map<String, AttributeValue> getItem(String tenantId, String uuid) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of(
                        "tenantId", AttributeValue.fromS(tenantId),
                        "uuid", AttributeValue.fromS(uuid)
                ))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            throw new IllegalArgumentException(
                    "No DynamoDB record found for tenantId=" + tenantId + " uuid=" + uuid);
        }

        log.info("Fetched CSAT-score record tenantId={} uuid={}", tenantId, uuid);
        return response.item();
    }

    /**
     * Writes {@code predicted_csat_score} + {@code prediction_metadata} onto an existing
     * row without touching topics or subtopics. Suitable for a re-score / backfill path
     * where the TopicAI pipeline has already populated the topic columns.
     *
     * @param tenantId    partition key
     * @param uuid        sort key (the transcript id)
     * @param prediction  full LLM prediction; {@code predictedCsat} must be non-null
     */
    public void updatePrediction(String tenantId, String uuid, CsatPrediction prediction) {
        if (prediction == null || prediction.getPredictedCsat() == null) {
            throw new IllegalArgumentException("prediction and prediction.predictedCsat are required");
        }

        Map<String, AttributeValue> key = Map.of(
                "tenantId", AttributeValue.fromS(tenantId),
                "uuid", AttributeValue.fromS(uuid)
        );

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":predicted", AttributeValue.fromN(
                String.format("%.2f", prediction.getPredictedCsat().doubleValue())));
        values.put(":metadata", predictionMetadata(prediction));

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET predicted_csat_score = :predicted, prediction_metadata = :metadata")
                .expressionAttributeValues(values)
                .build());

        log.info("Updated CSAT prediction tenantId={} uuid={} score={}",
                tenantId, uuid, prediction.getPredictedCsat());
    }

    /**
     * Packs a {@link CsatPrediction} into a DynamoDB Map attribute so every LLM field
     * (reasoning, confidence, opening/closing sentiment, sentiment trajectory,
     * resolution status, effort level, escalation flag, recovery flag, agent effort,
     * repeat-contact and churn risks, topicsDetected, drivers, topKeyPhrases,
     * nextBestAction, rationale) plus a server-side {@code predictedAt} timestamp
     * travel together on the row. Null/blank fields are omitted so the stored shape
     * stays compact.
     */
    private static AttributeValue predictionMetadata(CsatPrediction p) {
        Map<String, AttributeValue> m = new HashMap<>();
        if (p.getPredictedCsat() != null) {
            m.put("predictedCsat", AttributeValue.fromN(p.getPredictedCsat().toString()));
        }
        if (p.getConfidence() != null) {
            m.put("confidence", AttributeValue.fromN(p.getConfidence().toString()));
        }
        putStringIfPresent(m, "reasoning", p.getReasoning());
        putStringIfPresent(m, "openingSentiment", p.getOpeningSentiment());
        putStringIfPresent(m, "closingSentiment", p.getClosingSentiment());
        putStringIfPresent(m, "sentimentTrajectory", p.getSentimentTrajectory());
        putStringIfPresent(m, "resolutionStatus", p.getResolutionStatus());
        putStringIfPresent(m, "effortLevel", p.getEffortLevel());
        if (p.getEscalationRequested() != null) {
            m.put("escalationRequested", AttributeValue.fromBool(p.getEscalationRequested()));
        }
        if (p.getRecoveryDetected() != null) {
            m.put("recoveryDetected", AttributeValue.fromBool(p.getRecoveryDetected()));
        }
        putStringIfPresent(m, "agentEffort", p.getAgentEffort());
        putStringIfPresent(m, "predictedRepeatContactRisk", p.getPredictedRepeatContactRisk());
        putStringIfPresent(m, "predictedChurnRisk", p.getPredictedChurnRisk());
        putStringIfPresent(m, "nextBestAction", p.getNextBestAction());
        putStringIfPresent(m, "rationale", p.getRationale());
        List<String> topics = trimmed(p.getTopicsDetected());
        if (!topics.isEmpty()) {
            m.put("topicsDetected", AttributeValue.fromL(topics.stream()
                    .map(AttributeValue::fromS)
                    .collect(Collectors.toList())));
        }
        List<String> drivers = trimmed(p.getDrivers());
        if (!drivers.isEmpty()) {
            m.put("drivers", AttributeValue.fromL(drivers.stream()
                    .map(AttributeValue::fromS)
                    .collect(Collectors.toList())));
        }
        List<String> keyPhrases = trimmed(p.getTopKeyPhrases());
        if (!keyPhrases.isEmpty()) {
            m.put("topKeyPhrases", AttributeValue.fromL(keyPhrases.stream()
                    .map(AttributeValue::fromS)
                    .collect(Collectors.toList())));
        }
        m.put("predictedAt", AttributeValue.fromS(Instant.now().toString()));
        return AttributeValue.fromM(m);
    }

    private static void putStringIfPresent(Map<String, AttributeValue> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, AttributeValue.fromS(value));
        }
    }

    private static List<String> trimmed(List<String> in) {
        if (in == null) return List.of();
        return in.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());
    }
}
