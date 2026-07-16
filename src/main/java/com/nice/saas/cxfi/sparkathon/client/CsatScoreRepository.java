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
     * Updates an existing DynamoDB record with the list of detected topics and subtopics
     * returned by the TopicAI inference result.
     *
     * @param tenantId       partition key
     * @param uuid           sort key (transcript id)
     * @param topics         list of topic names from primaryTopic + secondaryTopics
     * @param subTopics      list of subTopic values from the TopicAI result (may be empty)
     */
    public void updateTopics(String tenantId, String uuid, List<String> topics, List<String> subTopics) {
        Map<String, AttributeValue> key = Map.of(
                "tenantId", AttributeValue.fromS(tenantId),
                "uuid", AttributeValue.fromS(uuid)
        );

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        StringBuilder updateExpr = new StringBuilder("SET ");

        if (topics != null && !topics.isEmpty()) {
            expressionValues.put(":topics", AttributeValue.fromSs(topics));
            updateExpr.append("topics_detected = :topics");
        }

        if (subTopics != null && !subTopics.isEmpty()) {
            expressionValues.put(":subtopics", AttributeValue.fromSs(subTopics));
            if (expressionValues.containsKey(":topics")) {
                updateExpr.append(", ");
            }
            updateExpr.append("sub_topics_detected = :subtopics");
        }

        if (expressionValues.isEmpty()) {
            log.info("No topics or subtopics to update for tenantId={} uuid={}", tenantId, uuid);
            return;
        }

        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression(updateExpr.toString())
                .expressionAttributeValues(expressionValues)
                .build());

        log.info("Updated topics for tenantId={} uuid={} topics={} subTopics={}",
                tenantId, uuid, topics, subTopics);
    }

     /* Fetches a single record by its primary key.
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
}
