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
 * <p>Queries {@code d6866-feedback-csat-scores} by {@code tenantId} then splits the rows
 * into two segments that are analysed differently:
 * <ul>
 *   <li><b>Respondents</b> — a survey score came back ({@code actual_csat_score} present).
 *       Ranked by actual CSAT; sampled by residual (actual − predicted) so the LLM can
 *       explain where prediction diverges from reality.</li>
 *   <li><b>Non-respondents</b> — no survey. Ranked and sampled on predicted CSAT alone.</li>
 * </ul>
 * All numbers are computed here in code; the LLM only narrates over them.
 */
@Component
public class InsightsAggregator {

    private static final Logger log = LoggerFactory.getLogger(InsightsAggregator.class);

    private static final String TABLE_NAME = "d6866-feedback-csat-scores";

    /** Max transcripts fed to the LLM per band (low / high) for each topic. */
    private static final int SAMPLES_PER_BAND = 4;
    /** A topic must have at least this many scored interactions to be ranked. */
    private static final int MIN_SCORED_PER_TOPIC = 2;
    /** |avgResidual| within this band means the predictor is considered aligned with reality. */
    private static final double ALIGN_TOLERANCE = 0.5;

    private static final String SEG_RESPONDENT = "respondent";
    private static final String SEG_NON_RESPONDENT = "non-respondent";

    private final DynamoDbClient dynamoDbClient;

    public InsightsAggregator(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public SegmentedAggregation aggregate(String tenantId) {
        List<Map<String, AttributeValue>> rows = queryAllRows(tenantId);
        log.info("Aggregating {} rows for tenant {}", rows.size(), tenantId);

        List<Map<String, AttributeValue>> respondentRows = new ArrayList<>();
        List<Map<String, AttributeValue>> nonRespondentRows = new ArrayList<>();
        for (Map<String, AttributeValue> row : rows) {
            (num(row, "actual_csat_score") != null ? respondentRows : nonRespondentRows).add(row);
        }

        Aggregation respondents = build(tenantId, SEG_RESPONDENT, respondentRows);
        Aggregation nonRespondents = build(tenantId, SEG_NON_RESPONDENT, nonRespondentRows);
        log.info("Segmented tenant {}: {} respondents, {} non-respondents",
                tenantId, respondentRows.size(), nonRespondentRows.size());
        return new SegmentedAggregation(tenantId, rows.size(), respondents, nonRespondents);
    }

    /** Builds one segment's aggregation. {@code respondent} toggles residual vs predicted logic. */
    private Aggregation build(String tenantId, String segment, List<Map<String, AttributeValue>> rows) {
        boolean respondent = SEG_RESPONDENT.equals(segment);

        Aggregation agg = new Aggregation();
        agg.setTenantId(tenantId);
        agg.setSegment(segment);
        agg.setTotalInteractions(rows.size());

        double predictedSum = 0;
        int predictedCount = 0;
        double actualSum = 0;
        int actualCount = 0;
        Map<String, Acc> byTopic = new LinkedHashMap<>();

        for (Map<String, AttributeValue> row : rows) {
            Double predicted = num(row, "predicted_csat_score");
            Double actual = num(row, "actual_csat_score");
            String s3Path = str(row, "transcript_s3_path");

            if (predicted != null) {
                predictedSum += predicted;
                predictedCount++;
            }
            if (actual != null) {
                actualSum += actual;
                actualCount++;
            }

            for (String topic : topics(row)) {
                Acc acc = byTopic.computeIfAbsent(topic, t -> new Acc());
                acc.count++;
                if (predicted != null) {
                    acc.predictedSum += predicted;
                    acc.scored++;
                    if (s3Path != null) {
                        acc.samples.add(new TranscriptSample(predicted, actual, s3Path));
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
        agg.setAvgActualCsat(respondent && actualCount > 0 ? round(actualSum / actualCount) : null);

        List<TopicAggregate> topics = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byTopic.entrySet()) {
            Acc a = e.getValue();
            allowed.add(e.getKey());
            if (a.scored < MIN_SCORED_PER_TOPIC) {
                continue; // thin-data guard
            }
            topics.add(toTopicAggregate(e.getKey(), a, respondent));
        }

        // Respondents rank by actual CSAT (ground truth); non-respondents by predicted.
        Comparator<TopicAggregate> order = respondent
                ? Comparator.comparingDouble(t -> t.getAvgActualCsat() == null ? Double.MAX_VALUE : t.getAvgActualCsat())
                : Comparator.comparingDouble(TopicAggregate::getAvgPredictedCsat);
        topics.sort(order);

        agg.setTopics(topics);
        agg.setAllowedTopics(allowed);
        return agg;
    }

    private TopicAggregate toTopicAggregate(String topic, Acc a, boolean respondent) {
        TopicAggregate t = new TopicAggregate();
        t.setTopic(topic);
        t.setCount(a.count);
        t.setScored(a.scored);
        t.setAvgPredictedCsat(round(a.predictedSum / a.scored));
        t.setActualResponses(a.actualResponses);
        t.setAvgActualCsat(a.actualResponses == 0 ? null : round(a.actualSum / a.actualResponses));

        List<TranscriptSample> sorted = new ArrayList<>(a.samples);
        if (respondent) {
            // Rank samples by residual (actual − predicted): most over-predicted first.
            sorted.removeIf(s -> s.residual() == null);
            sorted.sort(Comparator.comparingDouble(s -> s.residual()));
            double avgResidual = a.actualResponses == 0 ? 0
                    : round((a.actualSum - a.predictedSum) / a.scored);
            t.setAvgResidual(avgResidual);
            t.setDirection(direction(avgResidual));
        } else {
            // Rank samples by predicted CSAT: lowest first.
            sorted.sort(Comparator.comparingDouble(TranscriptSample::getPredictedCsat));
        }

        int band = Math.min(SAMPLES_PER_BAND, sorted.size() / 2);
        List<TranscriptSample> low = new ArrayList<>(sorted.subList(0, band));
        List<TranscriptSample> high = new ArrayList<>(sorted.subList(sorted.size() - band, sorted.size()));
        Collections.reverse(high);
        t.setLowSamples(low);
        t.setHighSamples(high);
        return t;
    }

    private static String direction(double avgResidual) {
        if (avgResidual <= -ALIGN_TOLERANCE) {
            return "over-predicted"; // model thought better than customers reported
        }
        if (avgResidual >= ALIGN_TOLERANCE) {
            return "under-predicted"; // model harsher than customers reported
        }
        return "aligned";
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
        /** Every scored interaction (predicted + actual + transcript path), banded later. */
        final List<TranscriptSample> samples = new ArrayList<>();
    }
}
