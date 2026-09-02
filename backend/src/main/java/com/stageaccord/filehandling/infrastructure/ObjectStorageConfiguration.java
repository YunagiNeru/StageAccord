package com.stageaccord.filehandling.infrastructure;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@Profile({"app", "worker"})
public class ObjectStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client objectStorageClient(@Value("${stage-accord.object-storage.region}") String region,
            @Value("${stage-accord.object-storage.endpoint}") URI endpoint,
            @Value("${stage-accord.object-storage.application.access-key-id}") String applicationAccessKey,
            @Value("${stage-accord.object-storage.application.secret-access-key}") String applicationSecretKey,
            @Value("${stage-accord.object-storage.worker.access-key-id}") String workerAccessKey,
            @Value("${stage-accord.object-storage.worker.secret-access-key}") String workerSecretKey,
            Environment environment) {
        String accessKey=environment.acceptsProfiles(Profiles.of("worker"))?workerAccessKey:applicationAccessKey;
        String secretKey=environment.acceptsProfiles(Profiles.of("worker"))?workerSecretKey:applicationSecretKey;
        return S3Client.builder().region(Region.of(region)).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner objectStoragePresigner(@Value("${stage-accord.object-storage.region}") String region,
            @Value("${stage-accord.object-storage.endpoint}") URI endpoint,
            @Value("${stage-accord.object-storage.application.access-key-id}") String applicationAccessKey,
            @Value("${stage-accord.object-storage.application.secret-access-key}") String applicationSecretKey,
            @Value("${stage-accord.object-storage.worker.access-key-id}") String workerAccessKey,
            @Value("${stage-accord.object-storage.worker.secret-access-key}") String workerSecretKey,
            Environment environment) {
        String accessKey=environment.acceptsProfiles(Profiles.of("worker"))?workerAccessKey:applicationAccessKey;
        String secretKey=environment.acceptsProfiles(Profiles.of("worker"))?workerSecretKey:applicationSecretKey;
        return S3Presigner.builder().region(Region.of(region)).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
