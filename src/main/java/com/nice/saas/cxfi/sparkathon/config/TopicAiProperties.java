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

    /** Path template for fetching a result; {correlationId} and tenantId query param are substituted at runtime. */
    private String resultPath = "/feedback-intelligence/transcripts/{correlationId}/topic-ai";

    /** Optional bearer token for authenticated environments. */
    private String bearerToken;

    /** Milliseconds to wait between polling attempts when waiting for a TopicAI result. */
    private long resultPollIntervalMs = 5000;

    /** Maximum milliseconds to wait for a TopicAI result before giving up. */
    private long resultPollTimeoutMs = 120000;

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

    public String getResultPath() {
        return resultPath;
    }

    public void setResultPath(String resultPath) {
        this.resultPath = resultPath;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public long getResultPollIntervalMs() {
        return resultPollIntervalMs;
    }

    public void setResultPollIntervalMs(long resultPollIntervalMs) {
        this.resultPollIntervalMs = resultPollIntervalMs;
    }

    public long getResultPollTimeoutMs() {
        return resultPollTimeoutMs;
    }

    public void setResultPollTimeoutMs(long resultPollTimeoutMs) {
        this.resultPollTimeoutMs = resultPollTimeoutMs;
    }
}
