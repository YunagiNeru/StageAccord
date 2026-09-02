package com.stageaccord.privacy.api;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.privacy.PrivacyService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

@Validated @RestController @Profile("app") @RequestMapping("/api/v1")
public final class PrivacyController {
    private static final String SESSION="__Host-stageaccord-session";private final PrivacyService privacy;
    public PrivacyController(PrivacyService privacy){this.privacy=privacy;}
    @PostMapping("/workspaces/{workspaceId}/data-exports")
    public ResponseEntity<IdResponse> requestWorkspaceExport(@CookieValue(value=SESSION,required=false)String session,
            @PathVariable UUID workspaceId,@Valid @RequestBody ExportRequest request){return created(privacy.requestWorkspaceExport(session,workspaceId,request.format()));}
    @PostMapping("/workspaces/{workspaceId}/deletion-requests")
    public ResponseEntity<IdResponse> requestWorkspaceDeletion(@CookieValue(value=SESSION,required=false)String session,
            @PathVariable UUID workspaceId){return created(privacy.requestWorkspaceDeletion(session,workspaceId));}
    @GetMapping("/deletion-requests/{deletionRequestId}")
    public JsonNode getDeletionRequest(@CookieValue(value=SESSION,required=false)String session,@PathVariable UUID deletionRequestId){
        return privacy.getDeletionRequest(session,deletionRequestId);}
    private static ResponseEntity<IdResponse> created(UUID id){return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));}
    public record IdResponse(UUID id){}public record ExportRequest(@NotBlank String format){}
}
