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
    private String gap;           // concrete difference between low- and high-scoring interactions
    private String what;
    private String why;
}
