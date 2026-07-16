package com.nice.saas.cxfi.sparkathon.insights;

import lombok.Data;

/**
 * A single sampled interaction chosen for contrastive analysis: its predicted CSAT, its
 * actual (survey) CSAT if the customer responded, and the {@code s3://} path to its
 * transcript. Carried from {@link InsightsAggregator} into the LLM prompt so the model
 * sees the numeric gap alongside the evidence.
 */
@Data
public class TranscriptSample {

    /** Predicted CSAT for this interaction. */
    private final double predictedCsat;

    /** Actual survey CSAT, or {@code null} for a non-respondent. */
    private final Double actualCsat;

    /** {@code s3://} path to this interaction's transcript. */
    private final String s3Path;

    public TranscriptSample(double predictedCsat, Double actualCsat, String s3Path) {
        this.predictedCsat = predictedCsat;
        this.actualCsat = actualCsat;
        this.s3Path = s3Path;
    }

    /** actual − predicted; {@code null} when the customer did not respond. */
    public Double residual() {
        return actualCsat == null ? null : round(actualCsat - predictedCsat);
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
