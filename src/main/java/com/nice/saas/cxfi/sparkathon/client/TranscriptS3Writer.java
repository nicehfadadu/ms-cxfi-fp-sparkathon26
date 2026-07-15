package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

/**
 * Persists generated artifacts to S3 under {@code d6866/<uuid>/<fileName>}.
 */
@Component
public class TranscriptS3Writer {

    private static final Logger log = LoggerFactory.getLogger(TranscriptS3Writer.class);

    private static final String BUCKET = "fi-sparkathon-d6866";
    private static final String KEY_PREFIX = "d6866/";

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public TranscriptS3Writer(S3Client s3Client, ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes the given object to JSON and uploads it under {@code d6866/<transcriptId>/<fileName>}.
     *
     * @return the {@code s3://} URI of the stored object
     */
    public String save(String transcriptId, String fileName, Object content) {
        String key = KEY_PREFIX + transcriptId + "/" + fileName;
        String json = toJson(content);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromString(json, StandardCharsets.UTF_8));

        String uri = "s3://" + BUCKET + "/" + key;
        log.info("Stored object at {}", uri);
        return uri;
    }

    private String toJson(Object content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize transcript to JSON", e);
        }
    }
}
