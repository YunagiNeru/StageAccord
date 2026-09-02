package com.stageaccord.agreement.api;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.agreement.AgreementProjectService;
import com.stageaccord.agreement.AgreementProjectService.CalendarException;
import com.stageaccord.agreement.AgreementProjectService.ScheduleItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1")
public final class AgreementProjectController {
    private static final String CREATOR_SESSION = "__Host-stageaccord-session";
    private static final String CLIENT_SESSION = "__Host-stageaccord-client";
    private final AgreementProjectService projects;

    public AgreementProjectController(AgreementProjectService projects) { this.projects = projects; }

    @PostMapping("/workspaces/{workspaceId}/requests/{requestId}/offers")
    public ResponseEntity<IdResponse> createOffer(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID requestId,
            @Valid @RequestBody CreateOfferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IdResponse(projects.createOffer(session, workspaceId, requestId, request.expiresAt())));
    }

    @PostMapping("/workspaces/{workspaceId}/offers/{offerId}/publications")
    public ResponseEntity<AgreementProjectService.OfferPublication> offerAgreementVersion(
            @CookieValue(value=CREATOR_SESSION,required=false) String session, @PathVariable UUID workspaceId,
            @PathVariable UUID offerId, @Valid @RequestBody AgreementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.offerAgreementVersion(session, workspaceId,
                offerId, request.agreement(), request.locale(), request.timeZone()));
    }

    @GetMapping("/client/projects/{projectAccessId}/agreements/current")
    public JsonNode getOfferedAgreement(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID projectAccessId) { return projects.getOfferedAgreement(session, projectAccessId); }

    @PostMapping("/client/agreements/{agreementVersionId}/acceptances")
    public IdResponse acceptAgreementVersion(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID agreementVersionId, @Valid @RequestBody AcceptanceRequest request) {
        return new IdResponse(projects.acceptAgreementVersion(session, agreementVersionId, request.displayedHash()));
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/change-orders")
    public ResponseEntity<IdResponse> proposeChangeOrder(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID projectId,
            @Valid @RequestBody ChangeOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(projects.proposeChangeOrder(session,
                workspaceId, projectId, request.diff(), request.reason(), request.scheduleImpact())));
    }

    @PostMapping("/client/change-orders/{changeOrderId}/acceptances")
    public ResponseEntity<Void> acceptChangeOrder(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID changeOrderId) { projects.acceptChangeOrder(session, changeOrderId); return ok(); }

    @GetMapping("/workspaces/{workspaceId}/projects")
    public List<JsonNode> listProjects(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId) { return projects.listProjects(session, workspaceId); }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}")
    public JsonNode getCreatorProject(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return projects.getCreatorProject(session, workspaceId, projectId);
    }

    @GetMapping("/client/projects/{projectAccessId}")
    public JsonNode getClientProject(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID projectAccessId) { return projects.getClientProject(session, projectAccessId); }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/holds")
    public ResponseEntity<Void> putProjectOnHold(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId) {
        projects.changeProjectStatus(session,workspaceId,projectId,"active","on_hold","PutProjectOnHold");return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/resumptions")
    public ResponseEntity<Void> resumeProject(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId) {
        projects.changeProjectStatus(session,workspaceId,projectId,"on_hold","active","ResumeProject");return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/disputes")
    public ResponseEntity<IdResponse> openCreatorDispute(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody ReasonRequest request) {
        return created(projects.openDispute(session,workspaceId,projectId,request.reason(),false));
    }

    @PostMapping("/client/projects/{projectAccessId}/disputes")
    public ResponseEntity<IdResponse> openClientDispute(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID projectAccessId,@Valid @RequestBody ReasonRequest request) {
        return created(projects.openClientDispute(session,projectAccessId,request.reason()));
    }

    @PatchMapping("/workspaces/{workspaceId}/projects/{projectId}/assignments")
    public ResponseEntity<Void> assignMember(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody AssignmentRequest request) {
        projects.assignMember(session,workspaceId,projectId,request.memberId(),request.scopeType(),request.scopeId());return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/checkpoints/{checkpointId}/submissions")
    public ResponseEntity<Void> submitCheckpoint(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID checkpointId) {
        projects.submitCheckpoint(session,workspaceId,checkpointId);return ok();
    }

    @PostMapping("/client/checkpoints/{checkpointId}/input-completions")
    public ResponseEntity<Void> completeClientInput(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID checkpointId) { projects.completeClientInput(session,checkpointId);return ok(); }

    @PutMapping("/workspaces/{workspaceId}/business-calendar")
    public IdResponse saveBusinessCalendar(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@Valid @RequestBody CalendarRequest request) {
        return new IdResponse(projects.saveBusinessCalendar(session,workspaceId,request.timeZone(),request.workdays(),
                request.cutoff(),request.exceptions()));
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/schedule-proposals")
    public ResponseEntity<IdResponse> proposeScheduleChange(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody ScheduleRequest request) {
        return created(projects.proposeScheduleChange(session,workspaceId,projectId,request.reason(),request.baseVersion(),
                request.changes(),request.finalDeliveryChanged(),request.items()));
    }

    @PostMapping("/client/schedule-proposals/{proposalId}/acceptances")
    public ResponseEntity<Void> acceptScheduleChange(@CookieValue(value=CLIENT_SESSION,required=false) String session,
            @PathVariable UUID proposalId) { projects.acceptScheduleChange(session,proposalId);return ok(); }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/tasks")
    public ResponseEntity<IdResponse> createInternalTask(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@Valid @RequestBody TaskRequest request) {
        return created(projects.createInternalTask(session,workspaceId,projectId,request.checkpointId(),request.title(),
                request.priority(),request.dueAt(),request.assigneeId()));
    }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/tasks")
    public List<JsonNode> listInternalTasks(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId) {
        return projects.listInternalTasks(session,workspaceId,projectId);
    }

    @PatchMapping("/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}")
    public ResponseEntity<Void> updateInternalTask(@CookieValue(value=CREATOR_SESSION,required=false) String session,
            @PathVariable UUID workspaceId,@PathVariable UUID projectId,@PathVariable UUID taskId,
            @Valid @RequestBody TaskUpdateRequest request) {
        projects.updateInternalTask(session,workspaceId,projectId,taskId,request.status(),request.expectedVersion());return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/cancellation-requests")
    public ResponseEntity<IdResponse> requestCreatorProjectCancellation(
            @CookieValue(value=CREATOR_SESSION,required=false) String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@Valid @RequestBody ReasonRequest request) {
        return created(projects.requestCancellation(session,workspaceId,projectId,request.reason(),false));
    }

    @PostMapping("/client/projects/{projectAccessId}/cancellation-requests")
    public ResponseEntity<IdResponse> requestClientProjectCancellation(
            @CookieValue(value=CLIENT_SESSION,required=false) String session,@PathVariable UUID projectAccessId,
            @Valid @RequestBody ReasonRequest request) {
        return created(projects.requestClientCancellation(session,projectAccessId,request.reason()));
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/cancellations/{id}/confirmations")
    public ResponseEntity<Void> confirmCreatorProjectCancellation(
            @CookieValue(value=CREATOR_SESSION,required=false) String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@PathVariable UUID id) {
        projects.confirmCancellation(session,workspaceId,projectId,id,false);return ok();
    }

    @PostMapping("/client/cancellations/{cancellationAccessId}/confirmations")
    public ResponseEntity<Void> confirmClientProjectCancellation(
            @CookieValue(value=CLIENT_SESSION,required=false) String session,@PathVariable UUID cancellationAccessId) {
        projects.confirmClientCancellation(session,cancellationAccessId);return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/disputes/{id}/resolutions")
    public ResponseEntity<Void> recordCreatorDisputeResolution(
            @CookieValue(value=CREATOR_SESSION,required=false) String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@PathVariable UUID id,@Valid @RequestBody ResolutionRequest request) {
        projects.resolveDispute(session,workspaceId,projectId,id,request.resolution(),false);return ok();
    }

    @PostMapping("/client/disputes/{disputeAccessId}/resolutions")
    public ResponseEntity<Void> recordClientDisputeResolution(
            @CookieValue(value=CLIENT_SESSION,required=false) String session,@PathVariable UUID disputeAccessId,
            @Valid @RequestBody ResolutionRequest request) {
        projects.resolveClientDispute(session,disputeAccessId,request.resolution());return ok();
    }

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/external-payment-records")
    public ResponseEntity<IdResponse> recordCreatorExternalPaymentStatus(
            @CookieValue(value=CREATOR_SESSION,required=false) String session,@PathVariable UUID workspaceId,
            @PathVariable UUID projectId,@Valid @RequestBody PaymentRequest request) {
        return created(projects.recordExternalPayment(session,workspaceId,projectId,request.provider(),request.reference(),
                request.amountMinor(),request.currency(),request.status(),false));
    }

    @PostMapping("/client/projects/{projectAccessId}/external-payment-records")
    public ResponseEntity<IdResponse> recordClientExternalPaymentStatus(
            @CookieValue(value=CLIENT_SESSION,required=false) String session,@PathVariable UUID projectAccessId,
            @Valid @RequestBody PaymentRequest request) {
        return created(projects.recordClientExternalPayment(session,projectAccessId,request.provider(),request.reference(),
                request.amountMinor(),request.currency(),request.status()));
    }

    private static ResponseEntity<Void> ok(){return ResponseEntity.noContent().build();}
    private static ResponseEntity<IdResponse> created(UUID id){return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));}
    public record IdResponse(UUID id) {}
    public record CreateOfferRequest(@NotNull @Future Instant expiresAt) {}
    public record AgreementRequest(@NotNull JsonNode agreement,@NotBlank String locale,@NotBlank String timeZone) {}
    public record AcceptanceRequest(@NotBlank @Size(min=64,max=64) String displayedHash) {}
    public record ChangeOrderRequest(@NotNull JsonNode diff,@NotBlank @Size(max=2000) String reason,@NotNull JsonNode scheduleImpact) {}
    public record ReasonRequest(@NotBlank @Size(max=2000) String reason) {}
    public record AssignmentRequest(@NotNull UUID memberId,@NotBlank String scopeType,@NotNull UUID scopeId) {}
    public record CalendarRequest(@NotBlank String timeZone,@NotNull JsonNode workdays,@NotNull LocalTime cutoff,
            @NotNull List<@Valid CalendarException> exceptions) {}
    public record ScheduleRequest(@NotBlank @Size(max=2000) String reason,@PositiveOrZero long baseVersion,
            @NotNull JsonNode changes,boolean finalDeliveryChanged,@NotEmpty List<@Valid ScheduleItem> items) {}
    public record TaskRequest(@NotNull UUID checkpointId,@NotBlank @Size(max=240) String title,@NotBlank String priority,
            @NotNull @Future Instant dueAt,@NotNull UUID assigneeId) {}
    public record TaskUpdateRequest(@NotBlank String status,@PositiveOrZero long expectedVersion) {}
    public record ResolutionRequest(@NotBlank String resolution) {}
    public record PaymentRequest(@NotBlank String provider,@NotBlank @Size(max=500) String reference,
            @Min(0) long amountMinor,@NotBlank @Size(min=3,max=3) String currency,@NotBlank String status) {}
}
