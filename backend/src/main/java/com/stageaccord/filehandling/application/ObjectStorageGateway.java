package com.stageaccord.filehandling.application;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public interface ObjectStorageGateway {
    String initiate(String objectKey);
    URI signPart(String objectKey, String providerUploadId, int partNumber, String checksumSha256,
            Duration lifetime);
    StoredObject complete(String objectKey, String providerUploadId, List<CompletedPart> parts);
    void abort(String objectKey, String providerUploadId);
    InputStream openQuarantined(String objectKey, String objectVersionId);
    StoredObject promote(String objectKey, String sourceVersionId);
    URI signCleanDownload(String objectKey, String objectVersionId, Duration lifetime);
    void deleteQuarantined(String objectKey, String objectVersionId);
    void deleteClean(String objectKey, String objectVersionId);

    record CompletedPart(int number, String etag, String checksumSha256) {}
    record StoredObject(String bucket, String versionId, long sizeBytes, String checksumSha256) {}
}
