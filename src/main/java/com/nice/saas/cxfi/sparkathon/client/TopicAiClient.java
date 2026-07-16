package com.nice.saas.cxfi.sparkathon.client;

import com.nice.saas.cxfi.sparkathon.config.TopicAiProperties;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptResponse;
import com.nice.saas.cxfi.sparkathon.model.TopicAiResultResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Client that calls the Feedback Intelligence {@code /topic-ai} send-transcript API
 * and polls for the TopicAI inference result.
 *
 * <p>The bearer token is sourced from AWS Secrets Manager via {@link TokenProvider}
 * rather than from application config.
 */
@Component
public class TopicAiClient {

    private static final Logger log = LoggerFactory.getLogger(TopicAiClient.class);

    private final RestClient restClient;
    private final TopicAiProperties properties;
    private final TokenProvider tokenProvider;

    public TopicAiClient(RestClient.Builder builder, TopicAiProperties properties,
                         TokenProvider tokenProvider) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Sends a transcript to the {@code /topic-ai} endpoint for TopicAI inference.
     *
     * @param request the transcript payload (a correlationId is generated if absent)
     * @return the accepted response including the correlationId
     */
    public SendTranscriptResponse sendTranscript(SendTranscriptRequest request) {
        if (!StringUtils.hasText(request.getCorrelationId())) {
            request.setCorrelationId(UUID.randomUUID().toString());
        }
        log.info("Sending transcript to {}{} — correlationId={}, tenantId={}, phrases={}",
                properties.getBaseUrl(), properties.getSendTranscriptPath(),
                request.getCorrelationId(), request.getTenantId(),
                request.getPhrases() == null ? 0 : request.getPhrases().size());

        var response = restClient.post()
                .uri(properties.getSendTranscriptPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getToken()))
                .body(request)
                .retrieve()
                .toEntity(String.class);

        SendTranscriptResponse result = new SendTranscriptResponse();
        result.setStatusCode(response.getStatusCode().value());
        result.setBody(response.getBody());
        result.setCorrelationId(request.getCorrelationId());
        log.info("Transcript accepted — status={}, correlationId={}",
                result.getStatusCode(), result.getCorrelationId());
        return result;
    }

    /**
     * Polls {@code GET /feedback-intelligence/transcripts/{correlationId}/topic-ai} until
     * a result is available or the configured timeout is reached.
     *
     * @param correlationId the transcript correlation id
     * @param tenantId      the tenant id
     * @return the TopicAI result, or {@code null} if the result was not available within the timeout
     */
    public TopicAiResultResponse pollForResult(String correlationId, String tenantId) {
        long deadline = System.currentTimeMillis() + properties.getResultPollTimeoutMs();
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            log.info("Polling for TopicAI result — correlationId={}, tenantId={}, attempt={}",
                    correlationId, tenantId, attempt);
            try {
                TopicAiResultResponse result = fetchResult(correlationId, tenantId);
                if (result != null) {
                    log.info("TopicAI result ready — correlationId={}, attempt={}", correlationId, attempt);
                    return result;
                }
            } catch (Exception e) {
                log.warn("Error polling TopicAI result — correlationId={}, attempt={}: {}",
                        correlationId, attempt, e.getMessage());
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(properties.getResultPollIntervalMs(), remaining));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("TopicAI result not available after {}ms — correlationId={}", properties.getResultPollTimeoutMs(), correlationId);
        return null;
    }

    /**
     * Single attempt to fetch the TopicAI result. Returns {@code null} if the result is not ready (404).
     */
    private TopicAiResultResponse fetchResult(String correlationId, String tenantId) {
        String path = properties.getResultPath()
                .replace("{correlationId}", correlationId)
                + "?tenantId=" + tenantId;

        return restClient.get()
                .uri(path)
                .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.getToken()))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    // 404 means not ready yet — return null rather than throw
                })
                .body(TopicAiResultResponse.class);
    }
}
