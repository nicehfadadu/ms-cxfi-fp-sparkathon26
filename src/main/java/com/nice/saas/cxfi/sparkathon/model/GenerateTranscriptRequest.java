package com.nice.saas.cxfi.sparkathon.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request from the UI to generate a sample transcript via Amazon Bedrock.
 */
@Data
public class GenerateTranscriptRequest {

    /** Topic dropdown key: billing, tech, order, cancel, refund, access, complaint. */
    @NotBlank
    private String topic;

    /** Temperament dropdown key: Calm, Positive, Frustrated, Mixed. */
    @NotBlank
    private String temperament;
}
