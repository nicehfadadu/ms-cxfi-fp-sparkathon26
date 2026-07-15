package com.nice.saas.cxfi.sparkathon.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response for a generated transcript, including the id used as its S3 folder.
 */
@Data
public class GenerateTranscriptResponse {

    /** UUID generated for this request; also the S3 folder under d6866/. */
    private String transcriptId;

    /** {@code s3://} URI where the transcript was stored. */
    private String s3Uri;

    /** {@code s3://} URI where the topic-ai transcript fragment was stored. */
    private String fragmentS3Uri;

    /** The generated turns, each a single-key object keyed by speaker. */
    private List<Map<String, String>> transcript;
}
