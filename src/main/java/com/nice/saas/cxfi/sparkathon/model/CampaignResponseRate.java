package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

/**
 * Per-campaign slice of {@link ResponseRateResponse}: response rate and average actual
 * CSAT for the interactions belonging to a single {@code campaign}.
 */
@Data
public class CampaignResponseRate {

    /** The campaign name (from the {@code campaign} attribute). */
    private String campaign;

    /** Interactions in this campaign. */
    private int totalInteractions;

    /** Interactions in this campaign that received an actual CSAT score. */
    private int responsesReceived;

    /** responsesReceived / totalInteractions for this campaign, as a percentage (0–100). */
    private double responseRatePercent;

    /** Average received actual CSAT for this campaign, or {@code null} if none received. */
    private Double avgActualCsat;
}
