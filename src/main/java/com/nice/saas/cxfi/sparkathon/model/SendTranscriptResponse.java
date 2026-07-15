package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

/**
 * Response from the {@code /topic-ai} API, which returns HTTP 202 Accepted.
 *
 * <p>The service may respond with an empty body or a {@code text/html} content type
 * (typical for a gateway 202), so the raw body is kept as a string and the
 * {@code correlationId} is parsed separately when the body is JSON.
 */
@Data
public class SendTranscriptResponse {

    /** Raw response body (may be empty for a 202 with no content). */
    private String body;

    private int statusCode;

    /** correlationId echoed by the service, or the one sent in the request as a fallback. */
    private String correlationId;
}
