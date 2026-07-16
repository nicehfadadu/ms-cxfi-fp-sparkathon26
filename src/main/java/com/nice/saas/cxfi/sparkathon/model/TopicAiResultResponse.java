package com.nice.saas.cxfi.sparkathon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Response from {@code GET /feedback-intelligence/transcripts/{correlationId}/topic-ai}.
 * Mirrors {@code TopicAiResult} + inner classes in ms-cxfi-eligibility-engine api-ext.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class    TopicAiResultResponse {

    private String version;
    private String processingType;
    private String tenantId;
    private String callerId;
    private Integer processingTime;
    private String startProcessingTime;
    private String messageSentTime;
    private String correlationId;
    private Payload payload;

    /** Serialized as {@code httpResponseCode} by the eligibility MS Jackson serializer. */
    private Integer httpResponseCode;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private TopicDetail primaryTopic;
        private List<TopicDetail> secondaryTopics;
        private List<TopicAction> actions;
    }

    /**
     * Mirrors {@code TopicDetail} in ms-cxfi-eligibility-engine.
     * {@code name} is the canonical topic label; {@code topic} / {@code subTopic} are the
     * hierarchical breakdown; {@code score} is the TopicAI confidence (0–1).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopicDetail {
        private String name;
        private String snippet;
        private String text;
        private Integer startOffset;
        private Integer endOffset;
        /** 0 = agent stream, 1 = customer stream (matches eligibility engine convention). */
        private Integer speaker;
        private String category;
        private String topic;
        private String subTopic;
        private Boolean callDriver;
        private Double score;
    }

    /**
     * Mirrors {@code TopicAction} in ms-cxfi-eligibility-engine.
     * Represents an agent action or event detected in the transcript.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopicAction {
        private String name;
        private String snippet;
        private String text;
        private Integer startOffsetMs;
        private Integer endOffsetMs;
        private Double score;
    }
}
