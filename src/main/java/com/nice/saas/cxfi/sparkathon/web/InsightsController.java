package com.nice.saas.cxfi.sparkathon.web;

import com.nice.saas.cxfi.sparkathon.insights.Aggregation;
import com.nice.saas.cxfi.sparkathon.insights.InsightGenerator;
import com.nice.saas.cxfi.sparkathon.insights.InsightsAggregator;
import com.nice.saas.cxfi.sparkathon.insights.SegmentedAggregation;
import com.nice.saas.cxfi.sparkathon.model.InsightsResponse;
import com.nice.saas.cxfi.sparkathon.model.InsightsSegment;
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
 * interactions from DynamoDB, split into respondents / non-respondents, sample transcripts
 * from S3, then ask Bedrock for prioritized actions per segment. Backs the "Show
 * Prediction" button.
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
        SegmentedAggregation agg = aggregator.aggregate(tenantId);

        List<RecommendedAction> respondentActions = generator.generateRespondent(agg.getRespondents());
        List<RecommendedAction> nonRespondentActions = generator.generateNonRespondent(agg.getNonRespondents());

        InsightsResponse response = new InsightsResponse();
        response.setTenantId(agg.getTenantId());
        response.setTotalInteractions(agg.getTotalInteractions());
        response.setRespondents(toSegment(agg.getRespondents(), respondentActions));
        response.setNonRespondents(toSegment(agg.getNonRespondents(), nonRespondentActions));
        return ResponseEntity.ok(response);
    }

    private static InsightsSegment toSegment(Aggregation agg, List<RecommendedAction> actions) {
        InsightsSegment seg = new InsightsSegment();
        seg.setSegment(agg.getSegment());
        seg.setTotalInteractions(agg.getTotalInteractions());
        seg.setPredictedScored(agg.getPredictedScored());
        seg.setAvgPredictedCsat(agg.getAvgPredictedCsat());
        seg.setAvgActualCsat(agg.getAvgActualCsat());
        seg.setActions(actions);
        return seg;
    }
}
