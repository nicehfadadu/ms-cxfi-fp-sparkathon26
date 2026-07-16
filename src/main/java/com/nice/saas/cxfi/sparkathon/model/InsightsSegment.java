package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * One analysed slice of {@link InsightsResponse}: either the respondents (customers who
 * returned a survey score) or the non-respondents. Carries the segment's headline numbers
 * plus its prioritized actions.
 */
@Data
public class InsightsSegment {

    /** "respondent" or "non-respondent". */
    private String segment;

    /** Interactions in this segment. */
    private int totalInteractions;

    /** Interactions with a predicted CSAT. */
    private int predictedScored;

    /** Average predicted CSAT for this segment. */
    private double avgPredictedCsat;

    /** Average actual (survey) CSAT — respondent segment only, else {@code null}. */
    private Double avgActualCsat;

    /** Prioritized actions, ranked worst-topic first. */
    private List<RecommendedAction> actions;
}
