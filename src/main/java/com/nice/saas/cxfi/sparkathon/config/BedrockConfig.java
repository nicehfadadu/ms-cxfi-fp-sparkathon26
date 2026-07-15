package com.nice.saas.cxfi.sparkathon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires the AWS clients (Bedrock runtime + S3).
 *
 * <p>Credentials: under the {@code local} Spring profile they come from a named AWS
 * profile ({@code AWS_PROFILE} env var, defaulting to "sparkathon"); otherwise the
 * default credentials chain is used (e.g. the deployed IAM role).
 * Region comes from {@code AWS_REGION} (default us-east-1).
 */
@Configuration
public class BedrockConfig {

    @Bean
    @Profile("local")
    public AwsCredentialsProvider localCredentialsProvider() {
        return ProfileCredentialsProvider.create(
                System.getenv().getOrDefault("AWS_PROFILE", "sparkathon"));
    }

    @Bean
    @Profile("!local")
    public AwsCredentialsProvider defaultCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient(AwsCredentialsProvider credentialsProvider) {
        return BedrockRuntimeClient.builder()
                .region(region())
                .credentialsProvider(credentialsProvider)
                .build();
    }

    @Bean
    public S3Client s3Client(AwsCredentialsProvider credentialsProvider) {
        return S3Client.builder()
                .region(region())
                .credentialsProvider(credentialsProvider)
                .build();
    }

    private Region region() {
        return Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
    }
}
