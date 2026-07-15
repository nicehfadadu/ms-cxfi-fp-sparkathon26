package com.nice.saas.cxfi.sparkathon.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Maps the UI topic dropdown key to the phrase injected into the prompt.
 */
public enum TopicType {

    BILLING("billing", "a billing dispute"),
    TECH("tech", "a technical support / troubleshooting issue"),
    ORDER("order", "an order status / tracking question"),
    CANCEL("cancel", "a cancellation where the agent tries to retain the customer"),
    REFUND("refund", "a refund request"),
    ACCESS("access", "an account access / password reset issue"),
    COMPLAINT("complaint", "a complaint that risks escalation");

    private final String key;
    private final String phrase;

    TopicType(String key, String phrase) {
        this.key = key;
        this.phrase = phrase;
    }

    public String getPhrase() {
        return phrase;
    }

    public static TopicType fromKey(String key) {
        return Arrays.stream(values())
                .filter(t -> t.key.equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown topic '" + key + "'. Expected one of: "
                                + Arrays.stream(values()).map(t -> t.key).collect(Collectors.joining(", "))));
    }
}
