package com.stageaccord.filehandling.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.filehandling.FileHandlingService;
import com.stageaccord.filehandling.FileHandlingService.DownloadGrant;
import com.stageaccord.filehandling.FileHandlingService.DownloadLocation;
import com.stageaccord.filehandling.FileHandlingService.PartCompletion;
import com.stageaccord.filehandling.FileHandlingService.SignedPart;
import com.stageaccord.filehandling.FileHandlingService.UploadInitiation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1")
public final class FileHandlingController {
    private static final String CREATOR_SESSION="__Host-stageaccord-session";
    private static final String CLIENT_SESSION="__Host-stageaccord-client";
    private final FileHandlingService files;
    public FileHandlingController(FileHandlingService files){this.files=files;}

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/uploads")
    public ResponseEntity<UploadInitiation> initiateCreatorMultipartUpload(
            @CookieValue(value=CREATOR_SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@Valid @RequestBody UploadRequest request){return ResponseEntity.status(HttpStatus.CREATED)
            .body(files.initiateCreatorUpload(session,workspaceId,projectId,request.logicalName(),request.mediaType(),request.sizeBytes()));}

    @PostMapping("/client/projects/{projectAccessId}/uploads")
    public ResponseEntity<UploadInitiation> initiateClientMultipartUpload(
            @CookieValue(value=CLIENT_SESSION,required=false)String session,@PathVariable UUID projectAccessId,
            @Valid @RequestBody UploadRequest request){return ResponseEntity.status(HttpStatus.CREATED)
            .body(files.initiateClientUpload(session,projectAccessId,request.logicalName(),request.mediaType(),request.sizeBytes()));}

    @PostMapping("/uploads/{uploadId}/parts/{partNumber}/signatures")
    public SignedPart signUploadPart(@CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client,@PathVariable UUID uploadId,
            @PathVariable @Min(1)@Max(60)int partNumber,@Valid @RequestBody SignPartRequest request){
        return files.signUploadPart(creator,client,uploadId,partNumber,request.sizeBytes(),request.checksumSha256());}

    @PostMapping("/uploads/{uploadId}/completion")
    public IdResponse completeUpload(@CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client,@PathVariable UUID uploadId,
            @Valid @RequestBody CompletionRequest request){return new IdResponse(files.completeUpload(creator,client,
                    uploadId,request.parts(),request.fullSha256()));}

    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> abortUpload(@CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client,@PathVariable UUID uploadId){
        files.abortUpload(creator,client,uploadId);return ResponseEntity.noContent().build();}

    @PostMapping("/workspaces/{workspaceId}/files/{fileVersionId}/download-grants")
    public ResponseEntity<DownloadGrant> issueCreatorDownloadGrant(
            @CookieValue(value=CREATOR_SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @PathVariable UUID fileVersionId){return ResponseEntity.status(HttpStatus.CREATED)
            .body(files.issueCreatorDownloadGrant(session,workspaceId,fileVersionId));}

    @PostMapping("/client/files/{fileAccessId}/download-grants")
    public ResponseEntity<DownloadGrant> issueClientDownloadGrant(
            @CookieValue(value=CLIENT_SESSION,required=false)String session,@PathVariable UUID fileAccessId){
        return ResponseEntity.status(HttpStatus.CREATED).body(files.issueClientDownloadGrant(session,fileAccessId));}

    @PostMapping("/download-grants/{grantId}/exchanges")
    public DownloadLocation exchangeDownloadGrant(@PathVariable UUID grantId,
            @RequestHeader("X-Download-Token")String token,
            @CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client){
        return files.exchangeDownloadGrant(grantId,token,creator,client);}

    @GetMapping("/downloads/{grantId}")
    public ResponseEntity<Void> downloadFile(@PathVariable UUID grantId,@RequestHeader("X-Download-Token")String token,
            @CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client){
        URI location=files.downloadFile(grantId,token,creator,client).url();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION,location.toString()).build();}

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/external-links")
    public ResponseEntity<IdResponse> registerCreatorExternalLink(
            @CookieValue(value=CREATOR_SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@Valid @RequestBody LinkRequest request){return created(
                    files.registerCreatorExternalLink(session,workspaceId,projectId,request.url()));}

    @PostMapping("/client/projects/{projectAccessId}/external-links")
    public ResponseEntity<IdResponse> registerClientExternalLink(
            @CookieValue(value=CLIENT_SESSION,required=false)String session,@PathVariable UUID projectAccessId,
            @Valid @RequestBody LinkRequest request){return created(files.registerClientExternalLink(session,projectAccessId,request.url()));}

    @PostMapping("/workspaces/{workspaceId}/files/{fileId}/deletion-requests")
    public ResponseEntity<Void> requestCreatorFileDeletion(
            @CookieValue(value=CREATOR_SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @PathVariable UUID fileId){files.requestCreatorFileDeletion(session,workspaceId,fileId);return accepted();}

    @PostMapping("/client/files/{fileAccessId}/deletion-requests")
    public ResponseEntity<Void> requestClientFileDeletion(
            @CookieValue(value=CLIENT_SESSION,required=false)String session,@PathVariable UUID fileAccessId){
        files.requestClientFileDeletion(session,fileAccessId);return accepted();}

    private static ResponseEntity<IdResponse> created(UUID id){return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));}
    private static ResponseEntity<Void> accepted(){return ResponseEntity.accepted().build();}
    public record IdResponse(UUID id){}
    public record UploadRequest(@NotBlank@Size(max=255)String logicalName,@NotBlank@Size(max=160)String mediaType,
            @Positive@Max(4_000_000_000L)long sizeBytes){}
    public record SignPartRequest(@Positive long sizeBytes,@NotBlank String checksumSha256){}
    public record CompletionRequest(@NotEmpty List<@Valid PartCompletion> parts,@NotBlank String fullSha256){}
    public record LinkRequest(@NotBlank@Size(max=2048)String url){}
}
