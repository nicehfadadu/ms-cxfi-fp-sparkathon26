package com.nice.saas.cxfi.sparkathon.client;

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

    private final DynamoDbClient dynamoDbClient;

    public CsatScoreRepository(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
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
     * Updates an existing DynamoDB record with detected topics, subtopics, and the
     * predicted CSAT score — all written in a single {@code UpdateItem} call.
     *
     * @param tenantId           partition key
     * @param uuid               sort key (transcript id)
     * @param topics             topic names from primaryTopic + secondaryTopics (stored as SS)
     * @param subTopics          subTopic values from the TopicAI result (stored as SS, may be empty)
     * @param predictedCsatScore predicted CSAT in [1.0, 5.0], or {@code null} if unavailable
     */
    public void updateTopics(String tenantId, String uuid,
                             List<String> topics, List<String> subTopics,
                             Double predictedCsatScore) {
        Map<String, AttributeValue> key = Map.of(
                "tenantId", AttributeValue.fromS(tenantId),
                "uuid", AttributeValue.fromS(uuid)
        );

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        List<String> setClauses = new java.util.ArrayList<>();

        if (topics != null && !topics.isEmpty()) {
            expressionValues.put(":topics", AttributeValue.fromSs(topics));
            setClauses.add("topics_detected = :topics");
        }

        if (subTopics != null && !subTopics.isEmpty()) {
            expressionValues.put(":subtopics", AttributeValue.fromSs(subTopics));
            setClauses.add("sub_topics_detected = :subtopics");
        }

        if (predictedCsatScore != null) {
            expressionValues.put(":predicted", AttributeValue.fromN(
                    String.format("%.2f", predictedCsatScore)));
            setClauses.add("predicted_csat_score = :predicted");
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
                tenantId, uuid, topics, subTopics, predictedCsatScore);
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
     * Fills in the prediction fields on an existing row created by {@link #save}. Called
     * after the chat is resolved/closed and the Bedrock CSAT predictor has scored it.
     *
     * @param tenantId              partition key
     * @param uuid                  sort key (the transcript id)
     * @param predictedCsatScore    predicted CSAT (1..5), required
     * @param predictionConfidence  model self-reported confidence 0..1, or {@code null}
     * @param topicsDetected        topics inferred from the chat, or {@code null}/empty
     */
    public void updatePrediction(String tenantId,
                                 String uuid,
                                 Double predictedCsatScore,
                                 Double predictionConfidence,
                                 List<String> topicsDetected) {

        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> sets = new ArrayList<>();

        names.put("#p", "predicted_csat_score");
        values.put(":p", AttributeValue.fromN(predictedCsatScore.toString()));
        sets.add("#p = :p");

        names.put("#u", "predicted_at");
        values.put(":u", AttributeValue.fromS(Instant.now().toString()));
        sets.add("#u = :u");

        if (predictionConfidence != null) {
            names.put("#c", "prediction_confidence");
            values.put(":c", AttributeValue.fromN(predictionConfidence.toString()));
            sets.add("#c = :c");
        }
        if (topicsDetected != null && !topicsDetected.isEmpty()) {
            names.put("#t", "topics_detected");
            values.put(":t", AttributeValue.fromSs(topicsDetected));
            sets.add("#t = :t");
        }

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("tenantId", AttributeValue.fromS(tenantId));
        key.put("uuid", AttributeValue.fromS(uuid));

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET " + String.join(", ", sets))
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());

        log.info("Updated CSAT prediction tenantId={} uuid={} score={}", tenantId, uuid, predictedCsatScore);
    }
}
