package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * Structured output of the CSAT-prediction Bedrock call. Mirrors the JSON schema
 * declared in {@code src/main/resources/prompts/csat-predict-prompt.txt}.
 */
@Data
public class CsatPrediction {

    /** Integer 1..5 (survey-parity). */
    private Integer predictedCsat;

    /** Model self-reported confidence in the score, 0.0..1.0. */
    private Double confidence;

    /** "declining" | "stable" | "improving" */
    private String sentimentTrajectory;

    /** "resolved" | "partial" | "unresolved" */
    private String resolutionStatus;

    /** "low" | "medium" | "high" */
    private String predictedRepeatContactRisk;

    /** "low" | "medium" | "high" */
    private String predictedChurnRisk;

    /** Topics inferred from the chat; empty when the transcript is too short or ambiguous. */
    private List<String> topicsDetected;

    /** Up to 3 short phrases explaining the score, grounded in transcript cues. */
    private List<String> drivers;

    /** One-sentence rationale, <=200 chars. */
    private String rationale;
}
