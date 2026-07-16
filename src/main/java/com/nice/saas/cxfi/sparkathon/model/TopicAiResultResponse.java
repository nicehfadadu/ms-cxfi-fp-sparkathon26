package com.nice.saas.cxfi.sparkathon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response from {@code GET /feedback-intelligence/transcripts/{correlationId}/topic-ai}
 * on the eligibility MS. Mirrors {@code TopicAiResult} in ms-cxfi-eligibility-engine.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TopicAiResultResponse {

    private String version;
    private String processingType;
    private String tenantId;
    private String callerId;
    private Integer processingTime;
    private String correlationId;
    private Payload payload;
    private Integer httpResponseCode;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private TopicDetail primaryTopic;
        private List<TopicDetail> secondaryTopics;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopicDetail {
        private String name;
        private String snippet;
        private String text;
        private String category;
        private String topic;
        private String subTopic;
        private Boolean callDriver;
        private Double score;
    }
}
