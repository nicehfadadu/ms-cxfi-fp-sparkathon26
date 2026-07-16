package com.nice.saas.cxfi.sparkathon.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides a valid CXone bearer token for the eligibility MS.
 *
 * <p>Flow:
 * <ol>
 *   <li>On first call (or when the cached token is near expiry), fetch the password from
 *       AWS Secrets Manager: secret {@code spakathon/fi}, key {@code PWD}.</li>
 *   <li>POST to the CXone login API with the hard-coded username and fetched password.</li>
 *   <li>Extract the {@code token} field from the JSON response and cache it.</li>
 *   <li>The cache is invalidated 5 minutes before the JWT {@code exp} claim so calls
 *       never use an expired token. Falls back to a 50-minute TTL if the token cannot
 *       be decoded as a JWT.</li>
 * </ol>
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    private static final String SECRET_ID =
            "arn:aws:secretsmanager:us-east-1:710894194408:secret:spakathon/fi-6eAoeJ";
    private static final String SECRET_PWD_KEY = "PWD";

    private static final String LOGIN_URL =
            "https://na1.dev.nice-incontact.com/public/authentication/v1/login";
    private static final String USERNAME =
            "harikrushna.fadadu_dev_topic_ai_it@nice.com";

    /** Refresh this many milliseconds before the JWT exp claim. */
    private static final long EXPIRY_BUFFER_MS = 5 * 60 * 1000L;
    /** Fallback TTL when the token cannot be decoded as a JWT. */
    private static final long FALLBACK_TTL_MS  = 50 * 60 * 1000L;

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile long   cacheExpiresAt = 0;

    public TokenProvider(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper,
                         RestClient.Builder restClientBuilder) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    /**
     * Returns a valid bearer token, refreshing via the CXone login API when necessary.
     */
    public String getToken() {
        if (System.currentTimeMillis() < cacheExpiresAt && cachedToken != null) {
            return cachedToken;
        }
        lock.lock();
        try {
            // Double-checked locking
            if (System.currentTimeMillis() < cacheExpiresAt && cachedToken != null) {
                return cachedToken;
            }
            String token = login(fetchPassword());
            cachedToken    = token;
            cacheExpiresAt = resolveExpiry(token);
            log.info("CXone token refreshed, cache valid until epoch-ms={}", cacheExpiresAt);
            return cachedToken;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Reads the PWD key from Secrets Manager. */
    private String fetchPassword() {
        GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                GetSecretValueRequest.builder().secretId(SECRET_ID).build());

        String secretString = response.secretString();
        if (secretString == null) {
            throw new IllegalStateException("Secret " + SECRET_ID + " has no string value");
        }
        try {
            Map<?, ?> map = objectMapper.readValue(secretString, Map.class);
            Object pwd = map.get(SECRET_PWD_KEY);
            if (pwd == null || pwd.toString().isBlank()) {
                throw new IllegalStateException(
                        "Key '" + SECRET_PWD_KEY + "' not found or empty in secret " + SECRET_ID);
            }
            return pwd.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse secret " + SECRET_ID + ": " + e.getMessage(), e);
        }
    }

    /**
     * POSTs credentials to the CXone login API and returns the bearer token.
     * Body: {"userName": "...", "password": "..."}
     * Response: {"token": "...", ...}
     */
    private String login(String password) {
        log.info("Requesting new CXone token for user={}", USERNAME);
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("userName", USERNAME, "password", password));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build login request body", e);
        }

        String responseBody = restClient.post()
                .uri(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response from CXone login API");
        }

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode tokenNode = json.get("token");
            if (tokenNode == null || tokenNode.isNull()) {
                throw new IllegalStateException("No 'token' field in CXone login response");
            }
            return tokenNode.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse CXone login response: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes the JWT payload (base64url) to read the {@code exp} claim.
     * Returns {@code now + EXPIRY_BUFFER_MS} before exp, falling back to
     * {@code now + FALLBACK_TTL_MS} if the token is not a valid JWT.
     */
    private long resolveExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return System.currentTimeMillis() + FALLBACK_TTL_MS;
            }
            // JWT payload is the second segment, base64url-encoded (no padding)
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode payload = objectMapper.readTree(decoded);
            JsonNode expNode = payload.get("exp");
            if (expNode == null || !expNode.isNumber()) {
                return System.currentTimeMillis() + FALLBACK_TTL_MS;
            }
            // exp is seconds since epoch; convert to ms and subtract buffer
            long expMs = expNode.longValue() * 1000L;
            return expMs - EXPIRY_BUFFER_MS;
        } catch (Exception e) {
            log.warn("Could not decode JWT exp claim, using fallback TTL: {}", e.getMessage());
            return System.currentTimeMillis() + FALLBACK_TTL_MS;
        }
    }

    private static String padBase64(String s) {
        return switch (s.length() % 4) {
            case 2 -> s + "==";
            case 3 -> s + "=";
            default -> s;
        };
    }
}
