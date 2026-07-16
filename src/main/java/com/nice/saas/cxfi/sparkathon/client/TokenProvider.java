package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads the eligibility MS bearer token from AWS Secrets Manager and caches it in memory.
 *
 * <p>Secret ARN: {@code arn:aws:secretsmanager:us-east-1:710894194408:secret:spakathon/fi-6eAoeJ}
 * Secret name: {@code spakathon/fi}
 * Key inside the JSON secret: {@code TOKEN}
 *
 * <p>The cached value is refreshed every {@link #CACHE_TTL_MS} milliseconds so the
 * application automatically picks up rotated tokens without a restart.
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    private static final String SECRET_ID =
            "arn:aws:secretsmanager:us-east-1:710894194408:secret:spakathon/fi-6eAoeJ";
    private static final String SECRET_KEY = "TOKEN";

    /** Re-fetch the secret every 10 minutes. */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile long cacheExpiresAt = 0;

    public TokenProvider(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the bearer token, fetching it from Secrets Manager if the cache has expired.
     */
    public String getToken() {
        if (System.currentTimeMillis() < cacheExpiresAt && cachedToken != null) {
            return cachedToken;
        }
        lock.lock();
        try {
            // Double-checked inside lock
            if (System.currentTimeMillis() < cacheExpiresAt && cachedToken != null) {
                return cachedToken;
            }
            cachedToken = fetchFromSecretsManager();
            cacheExpiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
            log.info("Bearer token refreshed from Secrets Manager (secret={})", SECRET_ID);
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchFromSecretsManager() {
        GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder().secretId(SECRET_ID).build());

        String secretString = response.secretString();
        if (secretString == null) {
            throw new IllegalStateException("Secret " + SECRET_ID + " has no string value");
        }

        try {
            Map<String, String> secretMap = objectMapper.readValue(secretString, Map.class);
            String token = secretMap.get(SECRET_KEY);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "Key '" + SECRET_KEY + "' not found or empty in secret " + SECRET_ID);
            }
            return token;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse secret " + SECRET_ID + ": " + e.getMessage(), e);
        }
    }
}
