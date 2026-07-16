package com.nice.saas.cxfi.sparkathon.insights;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches the handful of transcripts chosen by {@link InsightsAggregator} from S3 and
 * trims each to a short excerpt, so the LLM sees real evidence without a large payload.
 */
@Component
public class TranscriptSampler {

    private static final Logger log = LoggerFactory.getLogger(TranscriptSampler.class);

    /** Turns kept per sampled transcript. */
    private static final int MAX_TURNS = 12;

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public TranscriptSampler(S3Client s3Client, ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
    }

    /** Fetches and trims the transcripts at the given {@code s3://} URIs. */
    public List<String> excerpts(List<String> s3Uris) {
        List<String> out = new ArrayList<>();
        for (String uri : s3Uris) {
            try {
                out.add(excerpt(uri));
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable transcript {}: {}", uri, e.getMessage());
            }
        }
        return out;
    }

    private String excerpt(String s3Uri) {
        String[] bucketKey = splitUri(s3Uri);
        String body = getObject(bucketKey[0], bucketKey[1]);

        List<Map<String, String>> turns = parseTurns(body);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, String> turn : turns) {
            if (n++ >= MAX_TURNS) {
                break;
            }
            for (Map.Entry<String, String> e : turn.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String getObject(String bucket, String key) {
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read s3://" + bucket + "/" + key, e);
        }
    }

    private List<Map<String, String>> parseTurns(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Transcript is not a JSON array of turns", e);
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
