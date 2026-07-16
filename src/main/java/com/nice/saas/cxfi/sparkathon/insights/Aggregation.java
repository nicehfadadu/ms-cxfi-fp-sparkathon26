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

    /** "respondent" or "non-respondent" — which segment this aggregation covers. */
    private String segment;

    /** Interactions in this segment. */
    private int totalInteractions;

    /** Interactions with a non-null predicted CSAT. */
    private int predictedScored;

    /** Average predicted CSAT across this segment (scored rows). */
    private double avgPredictedCsat;

    /** Average actual (survey) CSAT — respondent segment only, else {@code null}. */
    private Double avgActualCsat;

    /**
     * Topics ranked worst-first. Non-respondent segment ranks by predicted CSAT ascending;
     * respondent segment ranks by actual CSAT ascending (we have ground truth there).
     */
    private List<TopicAggregate> topics = new ArrayList<>();

    /** Distinct topic labels the LLM is allowed to reference (verbatim from the data). */
    private List<String> allowedTopics = new ArrayList<>();
}
