package com.nice.saas.cxfi.sparkathon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the target Feedback Intelligence Topic AI service.
 */
@ConfigurationProperties(prefix = "topic-ai")
public class TopicAiProperties {

    /** Base URL of the eligibility-engine service, e.g. http://localhost:8080. */
    private String baseUrl = "http://localhost:8080";

    /** Path of the send-transcript endpoint. */
    private String sendTranscriptPath = "/feedback-intelligence/transcripts/topic-ai";

    /** Optional bearer token for authenticated environments. */
    private String bearerToken;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSendTranscriptPath() {
        return sendTranscriptPath;
    }

    public void setSendTranscriptPath(String sendTranscriptPath) {
        this.sendTranscriptPath = sendTranscriptPath;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }
}
