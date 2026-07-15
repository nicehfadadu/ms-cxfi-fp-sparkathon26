package com.nice.saas.cxfi.sparkathon.client;

import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts generated chat turns into the word-level "transcript fragment" that the
 * Feedback Intelligence {@code /topic-ai} endpoint consumes (a {@link SendTranscriptRequest}
 * whose phrases carry stream/word/offset timing). Timings are synthesized since the source
 * is text-only: each word occupies a fixed slot with small gaps between words and turns.
 */
@Component
public class TranscriptFragmentBuilder {

    private static final String TENANT_ID = "e2e-travel-hospitality-tenant";
    private static final String LANGUAGE = "en-US";
    private static final String MODEL_ID = "anthropic.claude-3-haiku";
    private static final String MODEL_VERSION = "1.0";

    private static final int WORD_DURATION_MS = 300;
    private static final int WORD_GAP_MS = 100;
    private static final int TURN_GAP_MS = 600;

    /**
     * Builds the topic-ai transcript fragment from the generated turns.
     *
     * @param transcriptId used as the fragment's correlationId
     * @param turns        ordered list of single-key objects keyed by speaker ("agent"/"customer")
     */
    public SendTranscriptRequest build(String transcriptId, List<Map<String, String>> turns) {
        List<SendTranscriptRequest.Phrase> phrases = new ArrayList<>();
        int cursor = 0;

        for (Map<String, String> turn : turns) {
            if (turn.isEmpty()) {
                continue;
            }
            Map.Entry<String, String> entry = turn.entrySet().iterator().next();
            String stream = "agent".equalsIgnoreCase(entry.getKey()) ? "Agent" : "Other";
            String text = entry.getValue() == null ? "" : entry.getValue().trim();

            for (String word : text.split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                SendTranscriptRequest.Phrase phrase = new SendTranscriptRequest.Phrase();
                phrase.setStream(stream);
                phrase.setWord(word);
                phrase.setOffsetMs(cursor);
                phrase.setEndOffsetMs(cursor + WORD_DURATION_MS);
                phrases.add(phrase);
                cursor += WORD_DURATION_MS + WORD_GAP_MS;
            }
            cursor += TURN_GAP_MS;
        }

        SendTranscriptRequest fragment = new SendTranscriptRequest();
        fragment.setTenantId(TENANT_ID);
        fragment.setLanguage(LANGUAGE);
        fragment.setCorrelationId(transcriptId);
        fragment.setModelId(MODEL_ID);
        fragment.setModelVersion(MODEL_VERSION);
        fragment.setPhrases(phrases);
        return fragment;
    }
}
