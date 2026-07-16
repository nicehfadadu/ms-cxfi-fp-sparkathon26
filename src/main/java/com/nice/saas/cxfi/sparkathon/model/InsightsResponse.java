package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

/**
 * Response of {@code GET /sparkathon/insights}: the tenant's interactions split into two
 * independently-analysed segments. Respondents are analysed by comparing actual vs
 * predicted CSAT; non-respondents on predicted CSAT alone.
 */
@Data
public class InsightsResponse {

    private String tenantId;

    /** Total interactions across both segments. */
    private int totalInteractions;

    /** Customers who returned a survey score — analysed on actual vs predicted. */
    private InsightsSegment respondents;

    /** Customers with no survey response — analysed on predicted CSAT alone. */
    private InsightsSegment nonRespondents;
}
