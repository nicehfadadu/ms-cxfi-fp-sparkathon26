package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * Response of {@code GET /sparkathon/csat/response-rate}: how many of a tenant's
 * interactions received an actual (survey) CSAT score, and the average of those scores,
 * broken down by {@code campaign}.
 *
 * <p>A "response" is counted when the {@code actual_csat_score} attribute is present on
 * the DynamoDB row — i.e. a survey score was received for that interaction. The top-level
 * numbers are the tenant totals; {@link #campaigns} carries the per-campaign breakdown.
 */
@Data
public class ResponseRateResponse {

    private String tenantId;

    /** Total interactions for the tenant (all rows in the partition). */
    private int totalInteractions;

    /** Interactions that received an actual CSAT score (rows with {@code actual_csat_score}). */
    private int responsesReceived;

    /** responsesReceived / totalInteractions, expressed as a percentage (0–100). */
    private double responseRatePercent;

    /** Average of the received actual CSAT scores, or {@code null} if none were received. */
    private Double avgActualCsat;

    /** Per-campaign breakdown, ordered by campaign name. */
    private List<CampaignResponseRate> campaigns;
}
