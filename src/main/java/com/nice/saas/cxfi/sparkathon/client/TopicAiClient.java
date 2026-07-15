package com.nice.saas.cxfi.sparkathon.client;

import com.nice.saas.cxfi.sparkathon.config.TopicAiProperties;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptRequest;
import com.nice.saas.cxfi.sparkathon.model.SendTranscriptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Client that calls the Feedback Intelligence {@code /topic-ai} send-transcript API.
 */
@Component
public class TopicAiClient {

    private static final Logger log = LoggerFactory.getLogger(TopicAiClient.class);

    private final RestClient restClient;
    private final TopicAiProperties properties;

    public TopicAiClient(RestClient.Builder builder, TopicAiProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Sends a transcript to the {@code /topic-ai} endpoint for TopicAI inference.
     *
     * @param request the transcript payload (a correlationId is generated if absent)
     * @return the accepted response including the correlationId
     */
    @SuppressWarnings("unchecked")
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
                .headers(headers -> {
                    if (StringUtils.hasText(properties.getBearerToken())) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getBearerToken());
                    }
                })
                .body(request)
                .retrieve()
                .toEntity(String.class);

        SendTranscriptResponse result = new SendTranscriptResponse();
        result.setStatusCode(response.getStatusCode().value());
        result.setBody( response.getBody());
        log.info("Transcript accepted — status={}, correlationId={}",
                result.getStatusCode(), result.getCorrelationId());
        return result;
    }
}
