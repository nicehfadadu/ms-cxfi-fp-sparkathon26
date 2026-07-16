package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;

/**
 * Payload for the Feedback Intelligence {@code POST /feedback-intelligence/transcripts/topic-ai} API.
 * Mirrors {@code com.nice.saas.wfo.api.service.SendTranscriptRequest} in ms-cxfi-eligibility-engine.
 */
@Data
public class SendTranscriptRequest {

    private String tenantId = "11f16fc2-78c4-d4a0-b24e-0242ac110002";
    private String language;
    private String correlationId;
    private String modelId;
    private String modelVersion;
    private List<Phrase> phrases;

    @Data
    public static class Phrase {
        private String stream;
        private String word;
        private int offsetMs;
        private int endOffsetMs;
    }
}
