package com.stageaccord.filehandling;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.filehandling.application.ObjectStorageGateway;
import com.stageaccord.filehandling.application.ObjectStorageGateway.CompletedPart;
import com.stageaccord.filehandling.application.ExternalLinkValidator;
import com.stageaccord.filehandling.domain.DownloadGrantPolicy;
import com.stageaccord.filehandling.domain.ExternalHostPolicy;
import com.stageaccord.filehandling.domain.FileRuleViolation;
import com.stageaccord.filehandling.domain.MultipartUploadPlan;
import com.stageaccord.filehandling.domain.MultipartUploadPlan.UploadedPart;
import com.stageaccord.identityaccess.api.AuthenticatedClient;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FileHandlingService {
    private static final Set<WorkspaceAccess> CONTRIBUTORS = Set.of(WorkspaceAccess.OWNER,
            WorkspaceAccess.ADMIN, WorkspaceAccess.PROJECT_MANAGER, WorkspaceAccess.MEMBER);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectStorageGateway storage;
    private final WorkspaceAccessGateway workspaceAccess;
    private final IdentityAccessGateway identities;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String scanMode;
    private final ExternalLinkValidator links = new ExternalLinkValidator(new ExternalHostPolicy());
    private final DownloadGrantPolicy grants = new DownloadGrantPolicy();

    public FileHandlingService(JdbcTemplate jdbc, ObjectMapper json, ObjectStorageGateway storage,
            WorkspaceAccessGateway workspaceAccess, IdentityAccessGateway identities, AuditRecorder audit,
            @Value("${stage-accord.malware-scan.mode}") String scanMode) {
        this(jdbc, json, storage, workspaceAccess, identities, audit, Clock.systemUTC(), scanMode);
    }

    FileHandlingService(JdbcTemplate jdbc, ObjectMapper json, ObjectStorageGateway storage,
            WorkspaceAccessGateway workspaceAccess, IdentityAccessGateway identities, AuditRecorder audit, Clock clock,
            String scanMode) {
        this.jdbc=jdbc;this.json=json;this.storage=storage;this.workspaceAccess=workspaceAccess;
        this.identities=identities;this.audit=audit;this.clock=clock;this.scanMode=scanMode;
    }

    @Transactional
    public UploadInitiation initiateCreatorUpload(String session, UUID workspaceId, UUID projectId,
            String logicalName, String mediaType, long sizeBytes) {
        UUID actor = workspaceAccess.requireMember(session,workspaceId,CONTRIBUTORS).accountId();
        requireProject(workspaceId,projectId);
        return initiate(workspaceId,projectId,"creator",actor,logicalName,mediaType,sizeBytes);
    }

    @Transactional
    public UploadInitiation initiateClientUpload(String session, UUID projectAccessId,
            String logicalName, String mediaType, long sizeBytes) {
        AuthenticatedClient client=requireProjectAccess(session,projectAccessId);
        return initiate(client.workspaceId(),client.projectId(),"client",client.sessionId(),logicalName,mediaType,sizeBytes);
    }

    private UploadInitiation initiate(UUID workspaceId,UUID projectId,String actorKind,UUID actorId,
            String logicalName,String mediaType,long sizeBytes) {
        MultipartUploadPlan plan=translate(() -> MultipartUploadPlan.forSize(sizeBytes));
        UUID id=UUID.randomUUID();
        String objectKey="workspaces/"+workspaceId+"/projects/"+projectId+"/uploads/"+id;
        String providerId;
        try { providerId=storage.initiate(objectKey); }
        catch (RuntimeException failure) { throw unavailable(); }
        var protectedName=identities.protectContact(logicalName);
        one(jdbc.update("INSERT INTO file_store.upload_session(workspace_id,project_id,id,object_key,max_size,part_size,"
                +"max_parts,checksum_algorithm,status,expires_at,provider_upload_id,actor_kind,actor_id,"
                +"logical_name_ciphertext,media_type) VALUES (?,?,?,?,?,?,?,'SHA256','initiated',?,?,?,?,?::jsonb,?)",
                workspaceId,projectId,id,objectKey,sizeBytes,MultipartUploadPlan.PART_SIZE,plan.partCount(),
                clock.instant().plus(Duration.ofHours(24)),providerId,actorKind,actorId,write(protectedName),mediaType));
        audit.recordAllowed(actorKind.equals("creator")?"InitiateCreatorMultipartUpload":"InitiateClientMultipartUpload",
                actorId,workspaceId);
        return new UploadInitiation(id,plan.partCount(),MultipartUploadPlan.PART_SIZE,clock.instant().plus(Duration.ofHours(24)));
    }

    @Transactional
    public SignedPart signUploadPart(String creatorSession,String clientSession,UUID uploadId,int partNumber,
            long sizeBytes,String checksumSha256) {
        Upload upload=upload(uploadId);
        authorizeUpload(upload,creatorSession,clientSession);
        long expected=partNumber==upload.partCount()?upload.totalSize()-MultipartUploadPlan.PART_SIZE*(upload.partCount()-1L)
                :MultipartUploadPlan.PART_SIZE;
        if(partNumber<1||partNumber>upload.partCount()||sizeBytes!=expected||decodeHash(checksumSha256).length!=32)
            throw invalid("INVALID_UPLOAD_PART");
        try {
            URI url=storage.signPart(upload.objectKey(),upload.providerId(),partNumber,checksumSha256,Duration.ofMinutes(15));
            return new SignedPart(url,clock.instant().plus(Duration.ofMinutes(15)));
        } catch(RuntimeException failure){throw unavailable();}
    }

    @Transactional
    public UUID completeUpload(String creatorSession,String clientSession,UUID uploadId,List<PartCompletion> parts,
            String fullSha256) {
        Upload upload=uploadForUpdate(uploadId);
        authorizeUpload(upload,creatorSession,clientSession);
        if(!List.of("initiated","uploading").contains(upload.status())||!clock.instant().isBefore(upload.expiresAt()))
            throw conflict();
        List<UploadedPart> verified=parts.stream().map(p->new UploadedPart(p.number(),p.sizeBytes(),decodeHash(p.checksumSha256()))).toList();
        translateVoid(() -> MultipartUploadPlan.forSize(upload.totalSize()).verifyCompletedParts(verified));
        ObjectStorageGateway.StoredObject stored;
        try { stored=storage.complete(upload.objectKey(),upload.providerId(),parts.stream()
                .map(p->new CompletedPart(p.number(),p.etag(),p.checksumSha256())).toList()); }
        catch(RuntimeException failure){throw unavailable();}
        if(stored.versionId()==null||stored.versionId().isBlank()||stored.sizeBytes()!=upload.totalSize())throw conflict();
        for(PartCompletion part:parts) one(jdbc.update("INSERT INTO file_store.upload_part(workspace_id,upload_id,part_no,"
                +"expected_size,actual_size,etag,checksum_sha256,completed_at) VALUES (?,?,?,?,?,?,?,?)",upload.workspaceId(),
                upload.id(),part.number(),part.sizeBytes(),part.sizeBytes(),part.etag(),decodeHash(part.checksumSha256()),clock.instant()));
        UUID fileId=UUID.randomUUID(),versionId=UUID.randomUUID();
        one(jdbc.update("INSERT INTO file_store.file_record(workspace_id,project_id,id,logical_name_ciphertext,owner_id,"
                +"deletion_status) VALUES (?,?,?,?::jsonb,?,'active')",upload.workspaceId(),upload.projectId(),fileId,
                upload.protectedName(),upload.actorId()));
        one(jdbc.update("INSERT INTO file_store.file_version(workspace_id,file_id,id,version_no,bucket,object_key,"
                +"object_version_id,size_bytes,sha256,status,scan_mode,scan_status,media_type) VALUES (?,?,?,1,?,?,?,?,?,"
                +"'scan_pending',?,'pending',?)",upload.workspaceId(),fileId,versionId,stored.bucket(),upload.objectKey(),
                stored.versionId(),stored.sizeBytes(),decodeHash(fullSha256),scanMode(),upload.mediaType()));
        one(jdbc.update("UPDATE file_store.upload_session SET status='completed',version=version+1 WHERE workspace_id=? "
                +"AND id=?",upload.workspaceId(),upload.id()));
        audit.recordAllowed("CompleteUpload",upload.actorId(),upload.workspaceId());
        return versionId;
    }

    @Transactional
    public void abortUpload(String creatorSession,String clientSession,UUID uploadId) {
        Upload upload=uploadForUpdate(uploadId);authorizeUpload(upload,creatorSession,clientSession);
        if(!List.of("initiated","uploading").contains(upload.status()))throw conflict();
        try{storage.abort(upload.objectKey(),upload.providerId());}catch(RuntimeException failure){throw unavailable();}
        one(jdbc.update("UPDATE file_store.upload_session SET status='aborted',version=version+1 WHERE workspace_id=? AND id=?",
                upload.workspaceId(),upload.id()));audit.recordAllowed("AbortUpload",upload.actorId(),upload.workspaceId());
    }

    @Transactional
    public DownloadGrant issueCreatorDownloadGrant(String session,UUID workspaceId,UUID fileVersionId){
        UUID actor=workspaceAccess.requireMember(session,workspaceId,CONTRIBUTORS).accountId();
        return issueGrant(workspaceId,fileVersionId,creatorGeneration(actor),actor,"IssueCreatorDownloadGrant");
    }

    @Transactional
    public DownloadGrant issueClientDownloadGrant(String session,UUID fileAccessId){
        AuthenticatedClient client=identities.resolveClient(session);
        boolean related=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM file_store.file_version v JOIN file_store.file_record f "
                +"ON f.workspace_id=v.workspace_id AND f.id=v.file_id WHERE v.id=? AND f.workspace_id=? AND f.project_id=?)",
                Boolean.class,fileAccessId,client.workspaceId(),client.projectId());
        if(!related)throw notFound();
        return issueGrant(client.workspaceId(),fileAccessId,client.authGeneration(),client.sessionId(),"IssueClientDownloadGrant");
    }

    private DownloadGrant issueGrant(UUID workspaceId,UUID fileVersionId,int generation,UUID actor,String operation){
        boolean ready=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM file_store.file_version WHERE workspace_id=? "
                +"AND id=? AND status='ready' AND scan_status IN ('clean','bypassed'))",Boolean.class,workspaceId,fileVersionId);
        if(!ready)throw invalid("FILE_NOT_READY");
        var token=identities.issueToken();UUID id=UUID.randomUUID();Instant expires=clock.instant().plus(Duration.ofMinutes(5));
        one(jdbc.update("INSERT INTO file_store.download_grant(workspace_id,id,file_version_id,token_digest,digest_key_id,"
                +"auth_generation,expires_at,remaining_uses) VALUES (?,?,?,?,?,?,?,1)",workspaceId,id,fileVersionId,
                token.digest(),token.digestKeyId(),generation,expires));audit.recordAllowed(operation,actor,workspaceId);
        return new DownloadGrant(id,token.plaintext(),expires);
    }

    @Transactional
    public DownloadLocation exchangeDownloadGrant(UUID grantId,String token,String creatorSession,String clientSession){
        Grant grant=grantForUpdate(grantId,token);int generation;boolean related;
        if(clientSession!=null){var client=identities.resolveClient(clientSession);generation=client.authGeneration();
            related=client.workspaceId().equals(grant.workspaceId())&&client.projectId().equals(grant.projectId());}
        else{var creator=identities.resolve(creatorSession);generation=creatorGeneration(creator.accountId());
            related=workspaceAccess.requireMember(creatorSession,grant.workspaceId(),CONTRIBUTORS)!=null;}
        translateVoid(()->grants.requireUsable(new DownloadGrantPolicy.Grant(grant.ready(),grant.authGeneration(),
                grant.expiresAt(),grant.revokedAt(),grant.remainingUses()),clock.instant(),
                new DownloadGrantPolicy.Authorization(true,generation,related)));
        URI url;try{url=storage.signCleanDownload(grant.objectKey(),grant.objectVersionId(),Duration.ofMinutes(1));}
        catch(RuntimeException failure){throw unavailable();}
        one(jdbc.update("UPDATE file_store.download_grant SET remaining_uses=remaining_uses-1 WHERE workspace_id=? AND id=? "
                +"AND remaining_uses>0",grant.workspaceId(),grant.id()));
        return new DownloadLocation(url,clock.instant().plus(Duration.ofMinutes(1)));
    }

    @Transactional
    public DownloadLocation downloadFile(UUID grantId,String token,String creatorSession,String clientSession){
        return exchangeDownloadGrant(grantId,token,creatorSession,clientSession);
    }

    @Transactional
    public UUID registerCreatorExternalLink(String session,UUID workspaceId,UUID projectId,String url){
        UUID actor=workspaceAccess.requireMember(session,workspaceId,CONTRIBUTORS).accountId();requireProject(workspaceId,projectId);
        return registerLink(workspaceId,projectId,actor,url,"RegisterCreatorExternalLink");
    }
    @Transactional
    public UUID registerClientExternalLink(String session,UUID projectAccessId,String url){
        var client=requireProjectAccess(session,projectAccessId);
        return registerLink(client.workspaceId(),client.projectId(),client.sessionId(),url,"RegisterClientExternalLink");
    }
    private UUID registerLink(UUID workspaceId,UUID projectId,UUID actor,String url,String operation){
        var valid=translate(()->links.validateWithoutNetworkAccess(url));UUID id=UUID.randomUUID();
        one(jdbc.update("INSERT INTO file_store.external_link(workspace_id,project_id,id,url_ciphertext,url_digest,host_ascii,status) "
                +"VALUES (?,?,?,?::jsonb,?,?,'active')",workspaceId,projectId,id,write(identities.protectContact(valid.normalizedUrl())),
                identities.tokenDigest(valid.normalizedUrl()),valid.asciiHost()));audit.recordAllowed(operation,actor,workspaceId);return id;
    }

    @Transactional public void requestCreatorFileDeletion(String session,UUID workspaceId,UUID fileId){
        UUID actor=workspaceAccess.requireMember(session,workspaceId,CONTRIBUTORS).accountId();deleteFile(workspaceId,fileId,actor,"RequestCreatorFileDeletion");}
    @Transactional public void requestClientFileDeletion(String session,UUID fileAccessId){
        var client=identities.resolveClient(session);UUID fileId=jdbc.query("SELECT f.id FROM file_store.file_version v JOIN "
                +"file_store.file_record f ON f.workspace_id=v.workspace_id AND f.id=v.file_id WHERE v.id=? AND f.workspace_id=? "
                +"AND f.project_id=?",(r,n)->r.getObject(1,UUID.class),fileAccessId,client.workspaceId(),client.projectId()).stream()
                .findFirst().orElseThrow(FileHandlingService::notFound);deleteFile(client.workspaceId(),fileId,client.sessionId(),"RequestClientFileDeletion");}
    private void deleteFile(UUID workspaceId,UUID fileId,UUID actor,String operation){
        one(jdbc.update("UPDATE file_store.file_record SET deletion_status='requested' WHERE workspace_id=? AND id=? "
                +"AND deletion_status='active'",workspaceId,fileId));jdbc.update("UPDATE file_store.download_grant SET revoked_at=? "
                +"WHERE workspace_id=? AND file_version_id IN (SELECT id FROM file_store.file_version WHERE workspace_id=? "
                +"AND file_id=?) AND revoked_at IS NULL",clock.instant(),workspaceId,workspaceId,fileId);audit.recordAllowed(operation,actor,workspaceId);}

    private Upload upload(UUID id){return findUpload(id,false);}private Upload uploadForUpdate(UUID id){return findUpload(id,true);}
    private Upload findUpload(UUID id,boolean lock){String sql="SELECT workspace_id,project_id,id,object_key,max_size,max_parts,status,expires_at,"
            +"provider_upload_id,actor_kind,actor_id,logical_name_ciphertext::text,media_type FROM file_store.upload_session WHERE id=?"
            +(lock?" FOR UPDATE":"");return jdbc.query(sql,(r,n)->new Upload(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getString(4),r.getLong(5),r.getInt(6),r.getString(7),r.getObject(8,Instant.class),
            r.getString(9),r.getString(10),r.getObject(11,UUID.class),r.getString(12),r.getString(13)),id).stream().findFirst()
            .orElseThrow(FileHandlingService::notFound);}
    private void authorizeUpload(Upload upload,String creator,String client){if("creator".equals(upload.actorKind()))
        workspaceAccess.requireMember(creator,upload.workspaceId(),CONTRIBUTORS);else{var c=identities.resolveClient(client);
        if(!c.workspaceId().equals(upload.workspaceId())||!c.projectId().equals(upload.projectId()))throw notFound();}}
    private AuthenticatedClient requireProjectAccess(String token,UUID accessId){var c=identities.resolveClient(token);boolean allowed=jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM project.client_access WHERE id=? AND workspace_id=? AND project_id=? AND revoked_at IS NULL AND expires_at>?)",
            Boolean.class,accessId,c.workspaceId(),c.projectId(),clock.instant());if(!allowed)throw notFound();return c;}
    private void requireProject(UUID workspaceId,UUID projectId){boolean found=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM project.project "
            +"WHERE workspace_id=? AND id=? AND status NOT IN ('cancelled','completed'))",Boolean.class,workspaceId,projectId);if(!found)throw notFound();}
    private int creatorGeneration(UUID accountId){return jdbc.queryForObject("SELECT auth_generation FROM iam.account WHERE id=?",Integer.class,accountId);}
    private Grant grantForUpdate(UUID id,String token){byte[] digest=identities.tokenDigest(token);return jdbc.query("SELECT g.workspace_id,g.id,"
            +"f.project_id,g.auth_generation,g.expires_at,g.revoked_at,g.remaining_uses,v.status='ready',v.object_key,v.object_version_id "
            +"FROM file_store.download_grant g JOIN file_store.file_version v ON v.workspace_id=g.workspace_id AND v.id=g.file_version_id "
            +"JOIN file_store.file_record f ON f.workspace_id=v.workspace_id AND f.id=v.file_id WHERE g.id=? AND g.token_digest=? FOR UPDATE OF g",
            (r,n)->new Grant(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getInt(4),
            r.getObject(5,Instant.class),r.getObject(6,Instant.class),r.getInt(7),r.getBoolean(8),r.getString(9),r.getString(10)),id,digest)
            .stream().findFirst().orElseThrow(FileHandlingService::notFound);}
    private String scanMode(){return scanMode;}
    private byte[] decodeHash(String value){try{return Base64.getDecoder().decode(value);}catch(IllegalArgumentException failure){throw invalid("INVALID_HASH");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JacksonException failure){throw invalid("INVALID_INPUT");}}
    private static <T>T translate(Action<T> action){try{return action.run();}catch(FileRuleViolation failure){throw invalid(failure.reason().name());}}
    private static void translateVoid(VoidAction action){try{action.run();}catch(FileRuleViolation failure){throw invalid(failure.reason().name());}}
    private static void one(int count){if(count!=1)throw conflict();}private static ApiFailure invalid(String code){return ApiFailure.of(HttpStatus.CONFLICT,code);}
    private static ApiFailure conflict(){return ApiFailure.of(HttpStatus.CONFLICT,"STATE_CONFLICT");}private static ApiFailure notFound(){return ApiFailure.of(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
    private static ApiFailure unavailable(){return ApiFailure.of(HttpStatus.SERVICE_UNAVAILABLE,"OBJECT_STORAGE_UNAVAILABLE");}
    @FunctionalInterface private interface Action<T>{T run();}@FunctionalInterface private interface VoidAction{void run();}
    public record UploadInitiation(UUID uploadId,int partCount,long partSize,Instant expiresAt){}
    public record SignedPart(URI url,Instant expiresAt){} public record PartCompletion(int number,long sizeBytes,String etag,String checksumSha256){}
    public record DownloadGrant(UUID grantId,String token,Instant expiresAt){} public record DownloadLocation(URI url,Instant expiresAt){}
    private record Upload(UUID workspaceId,UUID projectId,UUID id,String objectKey,long totalSize,int partCount,String status,
            Instant expiresAt,String providerId,String actorKind,UUID actorId,String protectedName,String mediaType){}
    private record Grant(UUID workspaceId,UUID id,UUID projectId,int authGeneration,Instant expiresAt,Instant revokedAt,
            int remainingUses,boolean ready,String objectKey,String objectVersionId){}
}
