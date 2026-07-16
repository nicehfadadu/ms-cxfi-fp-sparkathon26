package com.nice.saas.cxfi.sparkathon.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Maps the UI temperament dropdown key to the phrase injected into the prompt.
 */
public enum Temperament {

    CALM("Calm", "The customer is calm, polite and cooperative throughout."),
    POSITIVE("Positive", "The customer is upbeat, warm and appreciative, and thanks the agent often throughout."),
    FRUSTRATED("Frustrated", "The customer is clearly frustrated and difficult throughout, though never abusive."),
    ANGRY("Angry", "The customer is openly angry and confrontational throughout, using terse, sharp language, but never uses slurs or abuse."),
    ANXIOUS("Anxious", "The customer is anxious and worried throughout, repeatedly seeking reassurance that the problem will be fixed."),
    CONFUSED("Confused", "The customer is confused and unsure throughout, struggles to explain the issue, and needs clear step-by-step guidance."),
    IMPATIENT("Impatient", "The customer is impatient and in a hurry throughout, pushing for the fastest possible resolution."),
    SKEPTICAL("Skeptical", "The customer is skeptical and distrustful throughout, doubting the agent's answers and asking for proof or guarantees."),
    NEUTRAL("Neutral", "The customer is matter-of-fact and businesslike throughout, showing little emotion either way."),
    MIXED("Mixed", "The customer starts frustrated but gradually calms down as the agent helps them.");

    private final String key;
    private final String phrase;

    Temperament(String key, String phrase) {
        this.key = key;
        this.phrase = phrase;
    }

    public String getPhrase() {
        return phrase;
    }

    public static Temperament fromKey(String key) {
        return Arrays.stream(values())
                .filter(t -> t.key.equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown temperament '" + key + "'. Expected one of: "
                                + Arrays.stream(values()).map(t -> t.key).collect(Collectors.joining(", "))));
    }
}
