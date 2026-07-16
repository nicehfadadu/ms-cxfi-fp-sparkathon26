package com.nice.saas.cxfi.sparkathon.insights;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Full deterministic aggregation for one tenant: the full-population predicted CSAT
 * plus the topic rollups ranked worst-first. Feeds the sampler and the LLM.
 */
@Data
public class Aggregation {

    private String tenantId;

    /** Total interactions for the tenant (all rows). */
    private int totalInteractions;

    /** Interactions with a non-null predicted CSAT. */
    private int predictedScored;

    /** Average predicted CSAT across the whole population (scored rows). */
    private double avgPredictedCsat;

    /** Topics ranked ascending by predicted CSAT (worst first), after the min-count guard. */
    private List<TopicAggregate> topics = new ArrayList<>();

    /** Distinct topic labels the LLM is allowed to reference (verbatim from the data). */
    private List<String> allowedTopics = new ArrayList<>();
}
