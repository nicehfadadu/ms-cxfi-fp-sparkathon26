package com.nice.saas.cxfi.sparkathon.insights;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A single sampled interaction chosen for contrastive analysis: its predicted CSAT and
 * the {@code s3://} path to its transcript. Carried from {@link InsightsAggregator} into
 * the LLM prompt so the model sees the numeric gap alongside the evidence.
 */
@Data
@AllArgsConstructor
public class TranscriptSample {

    /** Predicted CSAT for this interaction. */
    private double predictedCsat;

    /** {@code s3://} path to this interaction's transcript. */
    private String s3Path;
}
