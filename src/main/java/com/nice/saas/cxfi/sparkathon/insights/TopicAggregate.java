package com.nice.saas.cxfi.sparkathon.insights;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic per-topic rollup produced by {@link InsightsAggregator}.
 * All numbers are computed in code; the LLM only narrates over them.
 */
@Data
public class TopicAggregate {

    /** Topic label, taken verbatim from {@code topics_detected} (never invented). */
    private String topic;

    /** Interactions tagged with this topic (regardless of scoring). */
    private int count;

    /** Interactions with a non-null predicted CSAT (denominator of the average). */
    private int scored;

    /** Average predicted CSAT over the scored interactions — the ranking signal. */
    private double avgPredictedCsat;

    /** Average survey CSAT over responders, or {@code null} — context only, never ranked on. */
    private Double avgActualCsat;

    /** Number of survey responses backing {@link #avgActualCsat}. */
    private int actualResponses;

    /** Lowest-scoring interactions for this topic (relative band), worst first. */
    private List<TranscriptSample> lowSamples = new ArrayList<>();

    /** Highest-scoring interactions for this topic (relative band), best first. */
    private List<TranscriptSample> highSamples = new ArrayList<>();
}
