package com.nice.saas.cxfi.sparkathon.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Maps the UI temperament dropdown key to the phrase injected into the prompt.
 */
public enum Temperament {

    CALM("Calm", "The customer is calm, polite and cooperative from the first turn through the closing turn. Their closing message is polite — a short thanks or a neutral acknowledgement."),
    POSITIVE("Positive", "The customer is upbeat, warm and appreciative throughout. Their closing message contains explicit appreciation ('thanks so much', 'you were amazing', 'really appreciate it')."),
    FRUSTRATED("Frustrated", "The customer is clearly frustrated and stays that way from the first turn through the closing turn — the agent's professionalism does NOT calm them down. Their closing message reflects unresolved anger: explicit complaint ('unacceptable', 'nightmare', 'worst experience'), grudging acceptance ('Ugh, fine', 'I guess I have no choice'), impatience, a threat (chargeback, cancellation, escalation, bad review), or sarcastic acknowledgement — never a thank-you, never appreciation."),
    MIXED("Mixed", "The customer STARTS frustrated but gradually calms down as the agent helps them, ending appreciative or at least polite. This is the ONLY temperament where the closing sentiment differs from the opening.");

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
