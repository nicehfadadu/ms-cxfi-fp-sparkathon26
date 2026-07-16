package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * Response of {@code GET /sparkathon/insights}: the full-population predicted CSAT
 * plus prioritized actions. Exactly the shape the insights UI renders.
 */
@Data
public class InsightsResponse {

    private String tenantId;

    /** Total interactions for the tenant. */
    private int totalInteractions;

    /** Interactions with a predicted CSAT (the average's denominator). */
    private int predictedScored;

    /** Full-population predicted CSAT — the summary banner number. */
    private double avgPredictedCsat;

    /** Recommended actions, ranked worst-topic first. */
    private List<RecommendedAction> actions;
}
