package com.stageaccord.collaboration.api;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.collaboration.CollaborationService;
import com.stageaccord.collaboration.CollaborationService.ApprovalRequest;
import com.stageaccord.collaboration.CollaborationService.DeliveryItemRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1")
public final class CollaborationController {
    private static final String CREATOR_SESSION="__Host-stageaccord-session";
    private static final String CLIENT_SESSION="__Host-stageaccord-client";
    private final CollaborationService collaboration;
    public CollaborationController(CollaborationService collaboration){this.collaboration=collaboration;}

    @PostMapping("/workspaces/{workspaceId}/checkpoints/{checkpointId}/progress-updates")
    public ResponseEntity<IdResponse> publishProgress(@CookieValue(value=CREATOR_SESSION,required=false)String session,
            @PathVariable UUID workspaceId,@PathVariable UUID checkpointId,@Valid @RequestBody ProgressRequest request){
        return created(collaboration.publishProgress(session,workspaceId,checkpointId,request.visibility(),request.body()));}

    @PostMapping("/client/checkpoints/{checkpointId}/comments")
    public ResponseEntity<IdResponse> postComment(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID checkpointId,@Valid @RequestBody CommentRequest request){return created(collaboration.postComment(
                    session,checkpointId,request.body(),request.targetType(),request.targetId(),request.targetVersion(),request.timeOffsetMs(),request.position()));}

    @PutMapping("/client/checkpoints/{checkpointId}/revision-draft")
    public IdResponse saveRevisionDraft(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID checkpointId,@Valid @RequestBody RevisionDraftRequest request){return new IdResponse(collaboration.saveRevisionDraft(
                    session,checkpointId,request.body(),request.targetType(),request.targetId(),request.targetVersion(),request.expectedVersion()));}

    @PostMapping("/client/checkpoints/{checkpointId}/revision-rounds")
    public ResponseEntity<IdResponse> submitRevisionRound(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID checkpointId,@Valid @RequestBody RevisionRoundRequest request){return created(collaboration.submitRevisionRound(
                    session,checkpointId,request.classification(),request.changeOrderId()));}

    @PostMapping("/client/checkpoints/{checkpointId}/approval-actions")
    public ResponseEntity<IdResponse> recordApprovalAction(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID checkpointId,@Valid @RequestBody ApprovalRequest request){return created(collaboration.recordApprovalAction(session,checkpointId,request));}

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/deliveries")
    public ResponseEntity<IdResponse> freezeDeliveryPackage(@CookieValue(value=CREATOR_SESSION,required=false)String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody DeliveryRequest request){return created(
                    collaboration.freezeDeliveryPackage(session,workspaceId,projectId,request.manifest(),request.terms(),request.credits(),request.notes(),request.items()));}

    @PostMapping("/client/deliveries/{deliveryId}/receipts")
    public ResponseEntity<Void> receiveDelivery(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID deliveryId,@Valid @RequestBody ReceiptRequest request){collaboration.receiveDelivery(session,deliveryId,
                    request.decision(),request.manifestSha256(),request.explicitUserAction());return ResponseEntity.noContent().build();}

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/exports")
    public ResponseEntity<IdResponse> exportCreatorProjectRecord(@CookieValue(value=CREATOR_SESSION,required=false)String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId){return created(collaboration.exportCreatorProjectRecord(session,workspaceId,projectId));}

    @PostMapping("/client/projects/{projectAccessId}/exports")
    public ResponseEntity<IdResponse> exportClientProjectRecord(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @PathVariable UUID projectAccessId){return created(collaboration.exportClientProjectRecord(session,projectAccessId));}

    @GetMapping("/notifications")
    public List<JsonNode> listNotifications(@CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client){return collaboration.listNotifications(creator,client);}

    @PostMapping("/notifications/{notificationId}/reads")
    public ResponseEntity<Void> markNotificationRead(@CookieValue(value=CREATOR_SESSION,required=false)String creator,
            @CookieValue(value=CLIENT_SESSION,required=false)String client,@PathVariable UUID notificationId){
        collaboration.markNotificationRead(creator,client,notificationId);return ResponseEntity.noContent().build();}

    @PutMapping("/workspaces/{workspaceId}/notification-preferences")
    public ResponseEntity<Void> updateNotificationPreference(@CookieValue(value=CREATOR_SESSION,required=false)String session,
            @PathVariable UUID workspaceId,@Valid @RequestBody PreferenceRequest request){collaboration.updateNotificationPreference(
                    session,workspaceId,request.category(),request.channel(),request.mode());return ResponseEntity.noContent().build();}

    @PutMapping("/client/notification-preferences")
    public ResponseEntity<Void> updateClientNotificationPreference(@CookieValue(value=CLIENT_SESSION,required=false)String session,
            @Valid @RequestBody PreferenceRequest request){collaboration.updateClientNotificationPreference(session,request.category(),
                    request.channel(),request.mode());return ResponseEntity.noContent().build();}

    private static ResponseEntity<IdResponse> created(UUID id){return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));}
    public record IdResponse(UUID id){}public record ProgressRequest(@NotBlank String visibility,@NotBlank@Size(max=20000)String body){}
    public record CommentRequest(@NotBlank@Size(max=20000)String body,@NotBlank String targetType,@NotNull UUID targetId,
            @Positive int targetVersion,@PositiveOrZero Long timeOffsetMs,JsonNode position){}
    public record RevisionDraftRequest(@NotBlank@Size(max=20000)String body,@NotBlank String targetType,@NotNull UUID targetId,
            @Positive int targetVersion,@PositiveOrZero long expectedVersion){}
    public record RevisionRoundRequest(@NotBlank String classification,UUID changeOrderId){}
    public record DeliveryRequest(@NotNull JsonNode manifest,@NotBlank@Size(max=20000)String terms,@NotBlank@Size(max=20000)String credits,
            @NotBlank@Size(max=20000)String notes,@NotEmpty List<@Valid DeliveryItemRequest> items){}
    public record ReceiptRequest(@NotBlank String decision,@NotBlank String manifestSha256,boolean explicitUserAction){}
    public record PreferenceRequest(@NotBlank String category,@NotBlank String channel,@NotBlank String mode){}
}
