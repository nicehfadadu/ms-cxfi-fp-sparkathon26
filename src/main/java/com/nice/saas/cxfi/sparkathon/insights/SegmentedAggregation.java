package com.nice.saas.cxfi.sparkathon.insights;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The tenant's interactions split into two independently-analysed segments.
 *
 * <p>Respondents (a survey score came back) are analysed by comparing actual vs predicted
 * CSAT; non-respondents (no survey) are analysed on predicted CSAT alone. Each segment
 * gets its own aggregation, sampling strategy and LLM prompt.
 */
@Data
@AllArgsConstructor
public class SegmentedAggregation {

    private String tenantId;

    /** Total interactions across both segments. */
    private int totalInteractions;

    /** Interactions that received an actual (survey) CSAT score. */
    private Aggregation respondents;

    /** Interactions with no survey response. */
    private Aggregation nonRespondents;
}
