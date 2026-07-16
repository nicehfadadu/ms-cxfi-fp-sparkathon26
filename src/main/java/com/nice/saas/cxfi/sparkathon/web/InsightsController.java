package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.insights.Aggregation;
import com.nice.saas.cxfi.sparkathon.insights.InsightGenerator;
import com.nice.saas.cxfi.sparkathon.insights.InsightsAggregator;
import com.nice.saas.cxfi.sparkathon.model.InsightsResponse;
import com.nice.saas.cxfi.sparkathon.model.RecommendedAction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Runs the full insights pipeline on every request (no cache): aggregate the tenant's
 * predicted CSAT and topics from DynamoDB, sample transcripts from S3, then ask Bedrock
 * for prioritized actions. Backs the "Show Prediction" button.
 */
@RestController
@RequestMapping("/sparkathon/insights")
@CrossOrigin
public class InsightsController {

    private final InsightsAggregator aggregator;
    private final InsightGenerator generator;

    public InsightsController(InsightsAggregator aggregator, InsightGenerator generator) {
        this.aggregator = aggregator;
        this.generator = generator;
    }

    @GetMapping
    public ResponseEntity<InsightsResponse> insights(@RequestParam String tenantId) {
        Aggregation agg = aggregator.aggregate(tenantId);
        List<RecommendedAction> actions = generator.generate(agg);

        InsightsResponse response = new InsightsResponse();
        response.setTenantId(agg.getTenantId());
        response.setTotalInteractions(agg.getTotalInteractions());
        response.setPredictedScored(agg.getPredictedScored());
        response.setAvgPredictedCsat(agg.getAvgPredictedCsat());
        response.setActions(actions);
        return ResponseEntity.ok(response);
    }
}
