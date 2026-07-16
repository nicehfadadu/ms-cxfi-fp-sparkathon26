package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

/**
 * A single prioritized action for the insights view. {@code topic} is always one of the
 * detected topics; {@code predictedCsat} and {@code chats} come from the deterministic
 * aggregation, while {@code what}/{@code why}/{@code priority} come from the LLM.
 */
@Data
public class RecommendedAction {

    private int rank;
    private String priority;      // high | medium | low
    private String topic;
    private double predictedCsat;
    private int chats;

    // Respondent-segment only (actual vs predicted). Null for the non-respondent segment.
    private Double actualCsat;    // average survey CSAT for the topic
    private Double residual;      // avg (actual − predicted)
    private String direction;     // over-predicted | under-predicted | aligned

    private String gap;           // respondent: the model gap (predicted vs actual). non-respondent: low-vs-high difference
    private String reason;        // respondent only: likely reason for the actual score / the divergence
    private String what;
    private String why;
}
