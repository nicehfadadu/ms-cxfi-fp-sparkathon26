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

    /** Average survey CSAT over responders, or {@code null}. Ranking signal for the respondent segment. */
    private Double avgActualCsat;

    /** Number of survey responses backing {@link #avgActualCsat}. */
    private int actualResponses;

    /** Average (actual − predicted) over responders — respondent segment only, else {@code null}. */
    private Double avgResidual;

    /**
     * Calibration verdict for the respondent segment: "over-predicted" (customers unhappier
     * than the model thought), "under-predicted", or "aligned". {@code null} for non-respondents.
     */
    private String direction;

    /**
     * Contrastive band A. Non-respondent segment: lowest predicted (worst first).
     * Respondent segment: most over-predicted (actual far below predicted).
     */
    private List<TranscriptSample> lowSamples = new ArrayList<>();

    /**
     * Contrastive band B. Non-respondent segment: highest predicted (best first).
     * Respondent segment: most under-predicted (actual far above predicted).
     */
    private List<TranscriptSample> highSamples = new ArrayList<>();
}
