package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;

/**
 * Reads objects stored in S3 and deserializes them.
 */
@Component
public class TranscriptS3Reader {

    private static final Logger log = LoggerFactory.getLogger(TranscriptS3Reader.class);

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public TranscriptS3Reader(S3Client s3Client, ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the object at the given {@code s3://} URI and deserializes it to the specified type.
     *
     * @param s3Uri  the {@code s3://bucket/key} URI
     * @param typeRef  the Jackson type reference for deserialization
     * @return the deserialized object
     */
    public <T> T read(String s3Uri, TypeReference<T> typeRef) {
        String[] bucketKey = splitUri(s3Uri);
        String bucket = bucketKey[0];
        String key = bucketKey[1];

        log.info("Reading s3://{}/{}", bucket, key);
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(body, typeRef);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
        }
    }

    private static String[] splitUri(String s3Uri) {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Not an s3:// URI: " + s3Uri);
        }
        String rest = s3Uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Missing key in URI: " + s3Uri);
        }
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }
}
