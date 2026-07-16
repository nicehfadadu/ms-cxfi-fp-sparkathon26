package com.nice.saas.cxfi.sparkathon.insights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic aggregation step of the insights pipeline.
 *
 * <p>Queries {@code d6866-feedback-csat-scores} by {@code tenantId} (the partition key),
 * then computes — in plain code, no LLM — the full-population predicted CSAT and a
 * per-topic rollup ranked worst-first. Topics come only from {@code topics_detected};
 * survey CSAT is carried as context but never drives ranking.
 */
@Component
public class InsightsAggregator {

    private static final Logger log = LoggerFactory.getLogger(InsightsAggregator.class);

    private static final String TABLE_NAME = "d6866-feedback-csat-scores";

    /** Max transcripts fed to the LLM per band (low / high) for each topic. */
    private static final int SAMPLES_PER_BAND = 4;
    /**
     * A topic must have at least this many scored interactions to be ranked. Two is the
     * minimum needed to form a low-vs-high contrast for the gap analysis.
     */
    private static final int MIN_SCORED_PER_TOPIC = 2;

    private final DynamoDbClient dynamoDbClient;

    public InsightsAggregator(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public Aggregation aggregate(String tenantId) {
        List<Map<String, AttributeValue>> rows = queryAllRows(tenantId);
        log.info("Aggregating {} rows for tenant {}", rows.size(), tenantId);

        Aggregation agg = new Aggregation();
        agg.setTenantId(tenantId);
        agg.setTotalInteractions(rows.size());

        double predictedSum = 0;
        int predictedCount = 0;
        Map<String, Acc> byTopic = new LinkedHashMap<>();

        for (Map<String, AttributeValue> row : rows) {
            Double predicted = num(row, "predicted_csat_score");
            Double actual = num(row, "actual_csat_score");
            String s3Path = str(row, "transcript_s3_path");

            if (predicted != null) {
                predictedSum += predicted;
                predictedCount++;
            }

            for (String topic : topics(row)) {
                Acc acc = byTopic.computeIfAbsent(topic, t -> new Acc());
                acc.count++;
                if (predicted != null) {
                    acc.predictedSum += predicted;
                    acc.scored++;
                    if (s3Path != null) {
                        acc.scoredSamples.add(new TranscriptSample(predicted, s3Path));
                    }
                }
                if (actual != null) {
                    acc.actualSum += actual;
                    acc.actualResponses++;
                }
            }
        }

        agg.setPredictedScored(predictedCount);
        agg.setAvgPredictedCsat(predictedCount == 0 ? 0 : round(predictedSum / predictedCount));

        List<TopicAggregate> topics = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byTopic.entrySet()) {
            Acc a = e.getValue();
            allowed.add(e.getKey());
            if (a.scored < MIN_SCORED_PER_TOPIC) {
                continue; // thin-data guard
            }
            TopicAggregate t = new TopicAggregate();
            t.setTopic(e.getKey());
            t.setCount(a.count);
            t.setScored(a.scored);
            t.setAvgPredictedCsat(round(a.predictedSum / a.scored));
            t.setActualResponses(a.actualResponses);
            t.setAvgActualCsat(a.actualResponses == 0 ? null : round(a.actualSum / a.actualResponses));

            // Relative banding: sort this topic's scored interactions by predicted CSAT and
            // take the bottom slice as LOW and the top slice as HIGH. This guarantees a
            // low-vs-high contrast even when every score sits in the same absolute range,
            // which fixed thresholds could not do.
            List<TranscriptSample> sorted = new ArrayList<>(a.scoredSamples);
            sorted.sort(Comparator.comparingDouble(TranscriptSample::getPredictedCsat));
            int band = Math.min(SAMPLES_PER_BAND, sorted.size() / 2);
            List<TranscriptSample> low = new ArrayList<>(sorted.subList(0, band));
            List<TranscriptSample> high = new ArrayList<>(sorted.subList(sorted.size() - band, sorted.size()));
            Collections.reverse(high); // best first
            t.setLowSamples(low);
            t.setHighSamples(high);
            topics.add(t);
        }
        topics.sort(Comparator.comparingDouble(TopicAggregate::getAvgPredictedCsat)); // worst first

        agg.setTopics(topics);
        agg.setAllowedTopics(allowed);
        return agg;
    }

    /** Pages through the tenant's partition with a Query (never a Scan). */
    private List<Map<String, AttributeValue>> queryAllRows(String tenantId) {
        List<Map<String, AttributeValue>> all = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder req = QueryRequest.builder()
                    .tableName(TABLE_NAME)
                    .keyConditionExpression("tenantId = :t")
                    .expressionAttributeValues(Map.of(":t", AttributeValue.fromS(tenantId)));
            if (startKey != null) {
                req.exclusiveStartKey(startKey);
            }
            QueryResponse resp = dynamoDbClient.query(req.build());
            all.addAll(resp.items());
            startKey = resp.hasLastEvaluatedKey() && !resp.lastEvaluatedKey().isEmpty()
                    ? resp.lastEvaluatedKey() : null;
        } while (startKey != null);
        return all;
    }

    private static Double num(Map<String, AttributeValue> row, String key) {
        AttributeValue v = row.get(key);
        if (v == null || v.n() == null) {
            return null;
        }
        try {
            return Double.parseDouble(v.n());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, AttributeValue> row, String key) {
        AttributeValue v = row.get(key);
        return v == null ? null : v.s();
    }

    /** topics_detected may be stored as a string set (SS) or a list (L). */
    private static List<String> topics(Map<String, AttributeValue> row) {
        AttributeValue v = row.get("topics_detected");
        if (v == null) {
            return List.of();
        }
        if (v.hasSs()) {
            return v.ss();
        }
        if (v.hasL()) {
            List<String> out = new ArrayList<>();
            for (AttributeValue e : v.l()) {
                if (e.s() != null) {
                    out.add(e.s());
                }
            }
            return out;
        }
        return List.of();
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** Mutable per-topic accumulator. */
    private static final class Acc {
        int count;
        int scored;
        double predictedSum;
        double actualSum;
        int actualResponses;
        /** Every scored interaction (predicted CSAT + transcript path), banded later. */
        final List<TranscriptSample> scoredSamples = new ArrayList<>();
    }
}
