package com.stageaccord.auditadmin.api;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.auditadmin.AuditAdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@Validated @RestController @Profile("app") @RequestMapping("/api/v1")
public final class AuditAdminController {
    private static final String CREATOR="__Host-stageaccord-session",CLIENT="__Host-stageaccord-client";
    private final AuditAdminService admin;public AuditAdminController(AuditAdminService admin){this.admin=admin;}
    @PostMapping("/admin/support-requests")
    public ResponseEntity<IdResponse> requestSupportGrant(@CookieValue(value=CREATOR,required=false)String session,
            @Valid @RequestBody SupportRequest request){return created(admin.requestSupportGrant(session,request.workspaceId(),request.projectId(),
                    request.ticketId(),request.purpose(),request.allowedOperations()));}
    @PostMapping("/admin/support-requests/{id}/approval")
    public ResponseEntity<IdResponse> approveSupportGrant(@CookieValue(value=CREATOR,required=false)String session,@PathVariable UUID id,
            @Valid @RequestBody ApprovalRequest request){return created(admin.approveSupportGrant(session,id,request.expiresAt()));}
    @PostMapping("/admin/kill-switches/{feature}/activations")
    public ResponseEntity<IdResponse> activateKillSwitch(@CookieValue(value=CREATOR,required=false)String session,@PathVariable String feature,
            @Valid @RequestBody KillSwitchRequest request){return created(admin.activateKillSwitch(session,request.workspaceId(),feature,request.reason(),request.releaseCondition()));}
    @PostMapping("/admin/kill-switches/{feature}/releases")
    public ResponseEntity<IdResponse> releaseKillSwitch(@CookieValue(value=CREATOR,required=false)String session,@PathVariable String feature,
            @Valid @RequestBody KillSwitchRequest request){return created(admin.releaseKillSwitch(session,request.workspaceId(),feature,request.reason(),request.releaseCondition()));}
    @PostMapping("/reports")
    public ResponseEntity<IdResponse> submitReport(@CookieValue(value=CREATOR,required=false)String creator,
            @CookieValue(value=CLIENT,required=false)String client,@Valid @RequestBody ReportRequest request){return created(admin.submitReport(creator,client,
                    request.workspaceId(),request.subjectType(),request.subjectId(),request.reasonCode(),request.detail()));}
    @GetMapping("/admin/reports")
    public List<JsonNode> listReports(@CookieValue(value=CREATOR,required=false)String session){return admin.listReports(session);}
    @PatchMapping("/admin/reports/{reportId}")
    public ResponseEntity<Void> updateReportDisposition(@CookieValue(value=CREATOR,required=false)String session,@PathVariable UUID reportId,
            @Valid @RequestBody DispositionRequest request){admin.updateReportDisposition(session,reportId,request.status());return ResponseEntity.noContent().build();}
    private static ResponseEntity<IdResponse> created(UUID id){return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));}
    public record IdResponse(UUID id){}public record SupportRequest(@NotNull UUID workspaceId,@NotNull UUID projectId,@NotBlank@Size(max=120)String ticketId,
            @NotBlank@Size(max=240)String purpose,@NotNull JsonNode allowedOperations){}public record ApprovalRequest(@NotNull@Future Instant expiresAt){}
    public record KillSwitchRequest(@NotNull UUID workspaceId,@NotBlank@Size(max=240)String reason,@NotBlank@Size(max=240)String releaseCondition){}
    public record ReportRequest(@NotNull UUID workspaceId,@NotBlank String subjectType,@NotNull UUID subjectId,@NotBlank@Size(max=80)String reasonCode,
            @NotBlank@Size(max=20000)String detail){}public record DispositionRequest(@NotBlank String status){}
}
