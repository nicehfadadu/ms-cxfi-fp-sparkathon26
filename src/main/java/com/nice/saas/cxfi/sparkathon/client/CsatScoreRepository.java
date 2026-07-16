package com.nice.saas.cxfi.sparkathon.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

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
}
