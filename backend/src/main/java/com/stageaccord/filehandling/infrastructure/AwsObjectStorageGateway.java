package com.stageaccord.filehandling.infrastructure;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.stageaccord.filehandling.application.ObjectStorageGateway;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

@Component
public final class AwsObjectStorageGateway implements ObjectStorageGateway {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String quarantineBucket;
    private final String cleanBucket;

    public AwsObjectStorageGateway(S3Client s3, S3Presigner presigner,
            @Value("${stage-accord.object-storage.quarantine-bucket}") String quarantineBucket,
            @Value("${stage-accord.object-storage.clean-bucket}") String cleanBucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.quarantineBucket = quarantineBucket;
        this.cleanBucket = cleanBucket;
    }

    @Override public String initiate(String objectKey) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(quarantineBucket)
                .key(objectKey).checksumAlgorithm(ChecksumAlgorithm.SHA256).build()).uploadId();
    }

    @Override public URI signPart(String objectKey, String providerUploadId, int partNumber,
            String checksumSha256, Duration lifetime) {
        var request = UploadPartRequest.builder().bucket(quarantineBucket).key(objectKey)
                .uploadId(providerUploadId).partNumber(partNumber).checksumSHA256(checksumSha256).build();
        return URI.create(presigner.presignUploadPart(UploadPartPresignRequest.builder().signatureDuration(lifetime)
                .uploadPartRequest(request).build()).url().toString());
    }

    @Override public StoredObject complete(String objectKey, String providerUploadId, List<CompletedPart> parts) {
        var completed = parts.stream().map(part -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                .partNumber(part.number()).eTag(part.etag()).checksumSHA256(part.checksumSha256()).build()).toList();
        var response = s3.completeMultipartUpload(builder -> builder.bucket(quarantineBucket).key(objectKey)
                .uploadId(providerUploadId).multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
        var head = s3.headObject(HeadObjectRequest.builder().bucket(quarantineBucket).key(objectKey)
                .versionId(response.versionId()).build());
        return new StoredObject(quarantineBucket, response.versionId(), head.contentLength(), head.checksumSHA256());
    }

    @Override public void abort(String objectKey, String providerUploadId) {
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(quarantineBucket).key(objectKey)
                .uploadId(providerUploadId).build());
    }

    @Override public InputStream openQuarantined(String objectKey, String objectVersionId) {
        return s3.getObject(GetObjectRequest.builder().bucket(quarantineBucket).key(objectKey)
                .versionId(objectVersionId).build());
    }

    @Override public StoredObject promote(String objectKey, String sourceVersionId) {
        String source = quarantineBucket + "/" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
                .replace("+", "%20") + "?versionId=" + URLEncoder.encode(sourceVersionId, StandardCharsets.UTF_8);
        var copied = s3.copyObject(CopyObjectRequest.builder().copySource(source).destinationBucket(cleanBucket)
                .destinationKey(objectKey).checksumAlgorithm(ChecksumAlgorithm.SHA256).build());
        var head = s3.headObject(HeadObjectRequest.builder().bucket(cleanBucket).key(objectKey)
                .versionId(copied.versionId()).build());
        return new StoredObject(cleanBucket, copied.versionId(), head.contentLength(), head.checksumSHA256());
    }

    @Override public URI signCleanDownload(String objectKey, String objectVersionId, Duration lifetime) {
        var get = GetObjectRequest.builder().bucket(cleanBucket).key(objectKey).versionId(objectVersionId).build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(lifetime)
                .getObjectRequest(get).build()).url().toString());
    }

    @Override public void deleteQuarantined(String objectKey, String objectVersionId) {
        delete(quarantineBucket, objectKey, objectVersionId);
    }
    @Override public void deleteClean(String objectKey, String objectVersionId) {
        delete(cleanBucket, objectKey, objectVersionId);
    }
    private void delete(String bucket, String key, String version) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(version).build());
    }
}
