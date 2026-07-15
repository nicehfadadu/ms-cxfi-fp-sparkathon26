package com.nice.saas.cxfi.sparkathon.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Maps the UI temperament dropdown key to the phrase injected into the prompt.
 */
public enum Temperament {

    CALM("Calm", "The customer is calm, polite and cooperative throughout."),
    POSITIVE("Positive", "The customer is upbeat, warm and appreciative, and thanks the agent often."),
    FRUSTRATED("Frustrated", "The customer is clearly frustrated and difficult, though never abusive."),
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
