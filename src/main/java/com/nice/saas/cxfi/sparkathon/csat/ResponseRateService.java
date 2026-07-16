package com.nice.saas.cxfi.sparkathon.csat;

import com.nice.saas.cxfi.sparkathon.model.CampaignResponseRate;
import com.nice.saas.cxfi.sparkathon.model.ResponseRateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes a tenant's survey response rate and average actual CSAT from
 * {@code d6866-feedback-csat-scores}.
 *
 * <p>A row counts as a "response received" when it carries an {@code actual_csat_score}
 * (a survey score came back for that interaction). The response rate is that count over
 * the total number of interactions in the tenant partition.
 */
@Component
public class ResponseRateService {

    private static final Logger log = LoggerFactory.getLogger(ResponseRateService.class);

    private static final String TABLE_NAME = "d6866-feedback-csat-scores";

    /** Bucket for rows that have no {@code campaign} attribute. */
    private static final String UNSPECIFIED_CAMPAIGN = "(unspecified)";

    private final DynamoDbClient dynamoDbClient;

    public ResponseRateService(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public ResponseRateResponse compute(String tenantId) {
        List<Map<String, AttributeValue>> rows = queryAllRows(tenantId);

        int total = rows.size();
        int responses = 0;
        double actualSum = 0;
        // Group accumulators by campaign (TreeMap => ordered by campaign name).
        Map<String, Acc> byCampaign = new TreeMap<>();

        for (Map<String, AttributeValue> row : rows) {
            String campaign = strOrDefault(row, "campaign", UNSPECIFIED_CAMPAIGN);
            Double actual = num(row, "actual_csat_score");

            Acc acc = byCampaign.computeIfAbsent(campaign, c -> new Acc());
            acc.total++;
            if (actual != null) {
                acc.responses++;
                acc.actualSum += actual;
                responses++;
                actualSum += actual;
            }
        }

        List<CampaignResponseRate> campaigns = new ArrayList<>();
        for (Map.Entry<String, Acc> e : byCampaign.entrySet()) {
            Acc a = e.getValue();
            CampaignResponseRate c = new CampaignResponseRate();
            c.setCampaign(e.getKey());
            c.setTotalInteractions(a.total);
            c.setResponsesReceived(a.responses);
            c.setResponseRatePercent(a.total == 0 ? 0 : round(a.responses * 100.0 / a.total));
            c.setAvgActualCsat(a.responses == 0 ? null : round(a.actualSum / a.responses));
            campaigns.add(c);
        }

        ResponseRateResponse resp = new ResponseRateResponse();
        resp.setTenantId(tenantId);
        resp.setTotalInteractions(total);
        resp.setResponsesReceived(responses);
        resp.setResponseRatePercent(total == 0 ? 0 : round(responses * 100.0 / total));
        resp.setAvgActualCsat(responses == 0 ? null : round(actualSum / responses));
        resp.setCampaigns(campaigns);

        log.info("Response rate for tenant {}: {}/{} = {}% avgActualCsat={} across {} campaign(s)",
                tenantId, responses, total, resp.getResponseRatePercent(), resp.getAvgActualCsat(),
                campaigns.size());
        return resp;
    }

    /** Mutable per-campaign accumulator. */
    private static final class Acc {
        int total;
        int responses;
        double actualSum;
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

    private static String strOrDefault(Map<String, AttributeValue> row, String key, String fallback) {
        AttributeValue v = row.get(key);
        if (v == null || v.s() == null || v.s().isBlank()) {
            return fallback;
        }
        return v.s();
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
