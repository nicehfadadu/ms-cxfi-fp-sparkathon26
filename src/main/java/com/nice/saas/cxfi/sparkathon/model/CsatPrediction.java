package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * Structured output of the CSAT-prediction Bedrock call. Mirrors the JSON schema
 * declared in {@code src/main/resources/prompts/csat-predict-prompt.txt}.
 */
@Data
public class CsatPrediction {

    /** 2-4 sentence chain-of-thought the model writes BEFORE committing to the score. */
    private String reasoning;

    /** Integer 1..5 (survey-parity). */
    private Integer predictedCsat;

    /** Model self-reported confidence in the score, 0.0..1.0. */
    private Double confidence;

    /** "positive" | "neutral" | "frustrated" | "angry" */
    private String openingSentiment;

    /** "appreciative" | "positive" | "neutral" | "resigned" | "negative" | "hostile" */
    private String closingSentiment;

    /** "declining" | "stable" | "improving" */
    private String sentimentTrajectory;

    /** "resolved" | "partial" | "unresolved" */
    private String resolutionStatus;

    /** "low" | "medium" | "high" — how much work the customer had to do to reach resolution. */
    private String effortLevel;

    /** True when the customer explicitly asked for a manager, chargeback, cancellation, legal action, or bad review. */
    private Boolean escalationRequested;

    /** True when the chat starts negative and ends appreciative/positive because of agent action. */
    private Boolean recoveryDetected;

    /** "low" | "adequate" | "strong" — captured separately so it does not influence the customer-outcome score. */
    private String agentEffort;

    /** "low" | "medium" | "high" */
    private String predictedRepeatContactRisk;

    /** "low" | "medium" | "high" */
    private String predictedChurnRisk;

    /** Topics inferred from the chat; empty when the transcript is too short or ambiguous. */
    private List<String> topicsDetected;

    /** Up to 3 short phrases explaining the score, grounded in transcript cues. */
    private List<String> drivers;

    /** Up to 3 verbatim quotes from the customer that most influenced the score. */
    private List<String> topKeyPhrases;

    /** One concrete follow-up the business should take for this customer; empty when predictedCsat is 4-5. */
    private String nextBestAction;

    /** One-sentence rationale, <=200 chars. */
    private String rationale;
}
