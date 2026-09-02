package com.stageaccord.publiccatalog.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.publiccatalog.CatalogIntakeService;
import com.stageaccord.publiccatalog.CatalogIntakeService.CheckpointDraft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1")
public final class CatalogIntakeController {
    private static final String SESSION_COOKIE = "__Host-stageaccord-session";
    private final CatalogIntakeService catalog;

    public CatalogIntakeController(CatalogIntakeService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/public/creators/{slug}")
    public JsonNode getPublicProjection(@PathVariable String slug) {
        return catalog.getPublicProjection(slug);
    }

    @GetMapping("/public/services/{serviceSlug}")
    public JsonNode getPublishedService(@PathVariable String serviceSlug) {
        return catalog.getPublishedService(serviceSlug);
    }

    @GetMapping("/public/services/{serviceSlug}/intake-form")
    public JsonNode getPublishedIntakeForm(@PathVariable String serviceSlug) {
        return catalog.getPublishedIntakeForm(serviceSlug);
    }

    @PutMapping("/workspaces/{workspaceId}/profile")
    public IdResponse updateProfileDraft(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @Valid @RequestBody ProfileDraftRequest request) {
        return new IdResponse(catalog.updateProfileDraft(session, workspaceId, request.slug(), request.draft()));
    }

    @PostMapping("/workspaces/{workspaceId}/profile/publications")
    public JsonNode publishProfile(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId) {
        return catalog.publishProfile(session, workspaceId);
    }

    @PatchMapping("/workspaces/{workspaceId}/profile/intake-status")
    public ResponseEntity<Void> setIntakeStatus(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @Valid @RequestBody StatusRequest request) {
        catalog.setIntakeStatus(session, workspaceId, request.status());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/workspaces/{workspaceId}/workflow-templates/{templateId}/draft")
    public IdResponse saveWorkflowDraft(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID templateId,
            @Valid @RequestBody WorkflowDraftRequest request) {
        return new IdResponse(catalog.saveWorkflowDraft(session, workspaceId, templateId,
                request.name(), request.checkpoints()));
    }

    @PostMapping("/workspaces/{workspaceId}/workflow-templates/{templateId}/publications")
    public IdResponse publishWorkflowVersion(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID templateId) {
        return new IdResponse(catalog.publishWorkflowVersion(session, workspaceId, templateId));
    }

    @GetMapping("/workspaces/{workspaceId}/workflow-templates")
    public List<JsonNode> listWorkflowTemplates(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId) {
        return catalog.listWorkflowTemplates(session, workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/workflow-templates/{templateId}")
    public JsonNode getWorkflowTemplate(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID templateId) {
        return catalog.getWorkflowTemplate(session, workspaceId, templateId);
    }

    @PostMapping("/workspaces/{workspaceId}/services")
    public ResponseEntity<IdResponse> saveServiceDraft(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @Valid @RequestBody ServiceDraftRequest request) {
        UUID id = catalog.saveServiceDraft(session, workspaceId, request.serviceId(), request.slug(),
                request.workflowVersionId(), request.content(), request.formSchema(), request.privacyTextVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(id));
    }

    @PostMapping("/workspaces/{workspaceId}/services/{serviceId}/publications")
    public IdResponse publishServiceVersion(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID serviceId) {
        return new IdResponse(catalog.publishServiceVersion(session, workspaceId, serviceId));
    }

    @PostMapping("/workspaces/{workspaceId}/services/{serviceId}/archives")
    public ResponseEntity<Void> archiveService(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID serviceId) {
        catalog.archiveService(session, workspaceId, serviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/{workspaceId}/services/{serviceId}/clones")
    public ResponseEntity<IdResponse> cloneService(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID serviceId,
            @Valid @RequestBody CloneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IdResponse(catalog.cloneService(session, workspaceId, serviceId, request.slug())));
    }

    @GetMapping("/workspaces/{workspaceId}/services")
    public List<JsonNode> listServices(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId) {
        return catalog.listServices(session, workspaceId);
    }

    @GetMapping("/workspaces/{workspaceId}/services/{serviceId}")
    public JsonNode getService(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID serviceId) {
        return catalog.getService(session, workspaceId, serviceId);
    }

    @PostMapping("/public/services/{serviceSlug}/requests")
    public ResponseEntity<SubmissionResponse> submitRequest(@PathVariable String serviceSlug,
            @Valid @RequestBody SubmissionRequest request) {
        var submitted = catalog.submitRequest(serviceSlug, request.email(), request.privacyTextVersion(),
                request.privacyAccepted(), request.botPassed(), request.answers());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubmissionResponse(submitted.requestId(), submitted.requestAccessId()));
    }

    @GetMapping("/workspaces/{workspaceId}/requests")
    public List<JsonNode> listRequests(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId) {
        return catalog.listRequests(session, workspaceId);
    }

    @PatchMapping("/workspaces/{workspaceId}/requests/{requestId}")
    public ResponseEntity<Void> classifyRequest(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID requestId,
            @Valid @RequestBody ClassificationRequest request) {
        catalog.classifyRequest(session, workspaceId, requestId, request.classification(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/{workspaceId}/requests/{requestId}/clarifications")
    public ResponseEntity<Void> requestClarification(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @PathVariable UUID requestId,
            @Valid @RequestBody ReasonRequest request) {
        catalog.requestClarification(session, workspaceId, requestId, request.reason());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/public/requests/{requestAccessId}/withdrawals")
    public ResponseEntity<Void> withdrawRequest(@PathVariable UUID requestAccessId) {
        catalog.withdrawRequest(requestAccessId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/workspaces/{workspaceId}/sender-blocks")
    public ResponseEntity<Void> blockSender(@CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID workspaceId, @Valid @RequestBody BlockRequest request) {
        catalog.blockSender(session, workspaceId, request.requestId(), request.reason(), request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    public record IdResponse(UUID id) {}
    public record ProfileDraftRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") @Size(max = 100) String slug,
            @NotNull JsonNode draft) {}
    public record StatusRequest(@NotBlank String status) {}
    public record WorkflowDraftRequest(@NotBlank @Size(max = 160) String name,
            @NotEmpty List<@NotNull CheckpointDraft> checkpoints) {}
    public record ServiceDraftRequest(UUID serviceId,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") @Size(max = 100) String slug,
            @NotNull UUID workflowVersionId, @NotNull JsonNode content, @NotNull JsonNode formSchema,
            @NotBlank @Size(max = 80) String privacyTextVersion) {}
    public record CloneRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") @Size(max = 100) String slug) {}
    public record SubmissionRequest(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 80) String privacyTextVersion, boolean privacyAccepted,
            boolean botPassed, @NotNull JsonNode answers) {}
    public record SubmissionResponse(UUID requestId, UUID requestAccessId) {}
    public record ClassificationRequest(@NotBlank String classification,
            @NotBlank @Size(max = 80) String reason) {}
    public record ReasonRequest(@NotBlank @Size(max = 80) String reason) {}
    public record BlockRequest(@NotNull UUID requestId, @NotBlank @Size(max = 80) String reason,
            @NotNull @Future Instant expiresAt) {}
}
