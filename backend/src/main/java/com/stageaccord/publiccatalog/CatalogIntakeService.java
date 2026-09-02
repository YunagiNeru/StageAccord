package com.stageaccord.publiccatalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.intake.api.IntakeGuard;
import com.stageaccord.publiccatalog.domain.PublicationPolicy;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CatalogIntakeService {
    private static final Set<WorkspaceAccess> EDITORS = Set.of(
            WorkspaceAccess.OWNER, WorkspaceAccess.ADMIN, WorkspaceAccess.PROJECT_MANAGER, WorkspaceAccess.MEMBER);
    private static final Set<WorkspaceAccess> PUBLISHERS = Set.of(
            WorkspaceAccess.OWNER, WorkspaceAccess.ADMIN, WorkspaceAccess.PROJECT_MANAGER);
    private static final List<String> PUBLIC_PROFILE_FIELDS = List.of(
            "displayName", "bio", "categories", "externalLinks", "intakeStatus", "capacityGuide");
    private static final List<String> PUBLIC_SERVICE_FIELDS = List.of(
            "title", "summary", "deliverables", "pricing", "leadTime", "requiredMaterials",
            "usageTerms", "workflow");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkspaceAccessGateway access;
    private final IdentityAccessGateway identities;
    private final IntakeGuard intake;
    private final AuditRecorder audit;
    private final PublicationPolicy publication = new PublicationPolicy();
    private final Clock clock;

    public CatalogIntakeService(JdbcTemplate jdbc, ObjectMapper json, WorkspaceAccessGateway access,
            IdentityAccessGateway identities, IntakeGuard intake, AuditRecorder audit) {
        this(jdbc, json, access, identities, intake, audit, Clock.systemUTC());
    }

    CatalogIntakeService(JdbcTemplate jdbc, ObjectMapper json, WorkspaceAccessGateway access,
            IdentityAccessGateway identities, IntakeGuard intake, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.identities = identities;
        this.intake = intake;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID updateProfileDraft(String session, UUID workspaceId, String slug, JsonNode draft) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        requireObject(draft);
        UUID profileId = jdbc.query("SELECT id FROM catalog.creator_profile WHERE workspace_id=?",
                (result, row) -> result.getObject(1, UUID.class), workspaceId).stream().findFirst()
                .orElseGet(UUID::randomUUID);
        int updated = jdbc.update("""
                INSERT INTO catalog.creator_profile(workspace_id,id,slug,draft_json,intake_status,version)
                VALUES (?,?,?,?::jsonb,'closed',0)
                ON CONFLICT (workspace_id,id) DO UPDATE
                SET slug=excluded.slug,draft_json=excluded.draft_json,version=creator_profile.version+1
                """, workspaceId, profileId, slug, write(draft));
        requireOne(updated);
        audit.recordAllowed("UpdateProfileDraft", actor.accountId(), workspaceId);
        return profileId;
    }

    @Transactional
    public JsonNode publishProfile(String session, UUID workspaceId) {
        var actor = access.requireMember(session, workspaceId, PUBLISHERS);
        ProfileRow profile = profile(workspaceId);
        ObjectNode projection = publicProjection(json, profile.draft());
        projection.put("intakeStatus", profile.intakeStatus());
        boolean complete = projection.hasNonNull("displayName") && !projection.get("displayName").asText().isBlank();
        requirePublishable(complete, hasPublishedWorkflow(workspaceId));
        UUID versionId = UUID.randomUUID();
        requireOne(jdbc.update("""
                INSERT INTO catalog.public_profile_projection(
                    workspace_id,profile_id,published_version_id,slug,public_json,published_at)
                VALUES (?,?,?,?,?::jsonb,?)
                ON CONFLICT (workspace_id,profile_id) DO UPDATE
                SET published_version_id=excluded.published_version_id,slug=excluded.slug,
                    public_json=excluded.public_json,published_at=excluded.published_at
                """, workspaceId, profile.id(), versionId, profile.slug(), write(projection), clock.instant()));
        requireOne(jdbc.update("UPDATE catalog.creator_profile SET published_version_id=?,version=version+1 "
                + "WHERE workspace_id=? AND id=?", versionId, workspaceId, profile.id()));
        audit.recordAllowed("PublishProfile", actor.accountId(), workspaceId);
        return projection;
    }

    @Transactional(readOnly = true)
    public JsonNode getPublicProjection(String slug) {
        return jdbc.query("SELECT public_json::text FROM catalog.public_profile_projection WHERE slug=?",
                (result, row) -> read(result.getString(1)), slug).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
    }

    @Transactional
    public void setIntakeStatus(String session, UUID workspaceId, String status) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        if (!Set.of("open", "paused", "closed").contains(status)) invalid();
        requireOne(jdbc.update("UPDATE catalog.creator_profile SET intake_status=?,version=version+1 "
                + "WHERE workspace_id=?", status, workspaceId));
        audit.recordAllowed("SetIntakeStatus", actor.accountId(), workspaceId);
    }

    @Transactional
    public UUID saveWorkflowDraft(String session, UUID workspaceId, UUID templateId,
            String name, List<CheckpointDraft> checkpoints) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        validateCheckpoints(checkpoints);
        WorkflowRow existing = jdbc.query("""
                SELECT id,version_no FROM catalog.workflow_template_version
                WHERE workspace_id=? AND template_id=? AND status='draft' FOR UPDATE
                """, (r, n) -> new WorkflowRow(r.getObject(1, UUID.class), r.getInt(2)),
                workspaceId, templateId).stream().findFirst().orElse(null);
        UUID versionId = existing == null ? UUID.randomUUID() : existing.id();
        int version = existing == null ? nextVersion("catalog.workflow_template_version", workspaceId,
                "template_id", templateId) : existing.version();
        if (existing == null) {
            requireOne(jdbc.update("INSERT INTO catalog.workflow_template_version"
                    + "(workspace_id,id,template_id,version_no,name,status) VALUES (?,?,?,?,?,'draft')",
                    workspaceId, versionId, templateId, version, name));
        } else {
            requireOne(jdbc.update("UPDATE catalog.workflow_template_version SET name=? WHERE workspace_id=? AND id=?",
                    name, workspaceId, versionId));
            jdbc.update("DELETE FROM catalog.checkpoint_template WHERE workspace_id=? AND workflow_version_id=?",
                    workspaceId, versionId);
        }
        for (CheckpointDraft item : checkpoints) {
            requireOne(jdbc.update("""
                    INSERT INTO catalog.checkpoint_template(
                        workspace_id,workflow_version_id,id,sequence_no,type,duration_days,
                        client_due_days,revision_policy_json)
                    VALUES (?,?,?,?,?,?,?,?::jsonb)
                    """, workspaceId, versionId, UUID.randomUUID(), item.sequence(), item.type(),
                    item.creatorDays(), item.clientDays(), write(item.policy())));
        }
        audit.recordAllowed("SaveWorkflowDraft", actor.accountId(), workspaceId);
        return versionId;
    }

    @Transactional
    public UUID publishWorkflowVersion(String session, UUID workspaceId, UUID templateId) {
        var actor = access.requireMember(session, workspaceId, PUBLISHERS);
        UUID id = jdbc.query("""
                SELECT id FROM catalog.workflow_template_version
                WHERE workspace_id=? AND template_id=? AND status='draft' FOR UPDATE
                """, (r, n) -> r.getObject(1, UUID.class), workspaceId, templateId).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
        int count = jdbc.queryForObject("SELECT count(*) FROM catalog.checkpoint_template "
                + "WHERE workspace_id=? AND workflow_version_id=?", Integer.class, workspaceId, id);
        requirePublishable(true, count > 0);
        requireOne(jdbc.update("UPDATE catalog.workflow_template_version SET status='published',published_at=? "
                + "WHERE workspace_id=? AND id=? AND status='draft'", clock.instant(), workspaceId, id));
        audit.recordAllowed("PublishWorkflowVersion", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> listWorkflowTemplates(String session, UUID workspaceId) {
        access.requireMember(session, workspaceId);
        return jdbc.query("""
                SELECT jsonb_build_object('templateId',template_id,'versionId',id,'version',version_no,
                    'name',name,'status',status)::text
                FROM catalog.workflow_template_version WHERE workspace_id=?
                ORDER BY template_id,version_no DESC
                """, (r, n) -> read(r.getString(1)), workspaceId);
    }

    @Transactional(readOnly = true)
    public JsonNode getWorkflowTemplate(String session, UUID workspaceId, UUID templateId) {
        access.requireMember(session, workspaceId);
        return jdbc.query("""
                SELECT jsonb_build_object('templateId',template_id,'versionId',id,'version',version_no,
                    'name',name,'status',status)::text
                FROM catalog.workflow_template_version WHERE workspace_id=? AND template_id=?
                ORDER BY version_no DESC LIMIT 1
                """, (r, n) -> read(r.getString(1)), workspaceId, templateId).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
    }

    @Transactional
    public UUID saveServiceDraft(String session, UUID workspaceId, UUID serviceId, String slug,
            UUID workflowVersionId, JsonNode content, JsonNode formSchema, String privacyVersion) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        UUID id = saveServiceDraftData(workspaceId, serviceId, slug, workflowVersionId,
                content, formSchema, privacyVersion);
        audit.recordAllowed("SaveServiceDraft", actor.accountId(), workspaceId);
        return id;
    }

    private UUID saveServiceDraftData(UUID workspaceId, UUID serviceId, String slug,
            UUID workflowVersionId, JsonNode content, JsonNode formSchema, String privacyVersion) {
        requireObject(content);
        requireObject(formSchema);
        ProfileRow profile = profile(workspaceId);
        UUID id = serviceId == null ? UUID.randomUUID() : serviceId;
        int exists = jdbc.queryForObject("SELECT count(*) FROM catalog.service WHERE workspace_id=? AND id=?",
                Integer.class, workspaceId, id);
        if (exists == 0) {
            requireOne(jdbc.update("INSERT INTO catalog.service(workspace_id,id,profile_id,slug,status) "
                    + "VALUES (?,?,?,?,'draft')", workspaceId, id, profile.id(), slug));
        }
        UUID versionId = UUID.randomUUID();
        int version = nextVersion("catalog.service_version", workspaceId, "service_id", id);
        requireOne(jdbc.update("""
                INSERT INTO catalog.service_version(
                    workspace_id,id,service_id,version_no,content_json,workflow_version_id,status)
                VALUES (?,?,?,?,?::jsonb,?,'draft')
                """, workspaceId, versionId, id, version, write(content), workflowVersionId));
        requireOne(jdbc.update("INSERT INTO catalog.intake_form_version"
                + "(workspace_id,id,service_version_id,version_no,schema_json,privacy_text_version,status) "
                + "VALUES (?,?,?,?,?::jsonb,?,'draft')", workspaceId, UUID.randomUUID(), versionId,
                version, write(formSchema), privacyVersion));
        return id;
    }

    @Transactional
    public UUID publishServiceVersion(String session, UUID workspaceId, UUID serviceId) {
        var actor = access.requireMember(session, workspaceId, PUBLISHERS);
        UUID versionId = jdbc.query("SELECT id FROM catalog.service_version WHERE workspace_id=? "
                + "AND service_id=? AND status='draft' ORDER BY version_no DESC LIMIT 1 FOR UPDATE",
                (r, n) -> r.getObject(1, UUID.class), workspaceId, serviceId).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
        requirePublishable(true, true);
        Instant now = clock.instant();
        requireOne(jdbc.update("UPDATE catalog.service_version SET status='published',published_at=? "
                + "WHERE workspace_id=? AND id=?", now, workspaceId, versionId));
        requireOne(jdbc.update("UPDATE catalog.intake_form_version SET status='published',published_at=? "
                + "WHERE workspace_id=? AND service_version_id=?", now, workspaceId, versionId));
        requireOne(jdbc.update("UPDATE catalog.service SET status='published',current_version_id=? "
                + "WHERE workspace_id=? AND id=?", versionId, workspaceId, serviceId));
        audit.recordAllowed("PublishServiceVersion", actor.accountId(), workspaceId);
        return versionId;
    }

    @Transactional
    public void archiveService(String session, UUID workspaceId, UUID serviceId) {
        var actor = access.requireMember(session, workspaceId, PUBLISHERS);
        requireOne(jdbc.update("UPDATE catalog.service SET status='archived' WHERE workspace_id=? AND id=?",
                workspaceId, serviceId));
        audit.recordAllowed("ArchiveService", actor.accountId(), workspaceId);
    }

    @Transactional
    public UUID cloneService(String session, UUID workspaceId, UUID sourceId, String slug) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        ServiceDraft source = serviceDraft(workspaceId, sourceId);
        UUID cloneId = saveServiceDraftData(workspaceId, null, slug, source.workflowVersionId(),
                source.content(), source.formSchema(), source.privacyVersion());
        audit.recordAllowed("CloneService", actor.accountId(), workspaceId);
        return cloneId;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> listServices(String session, UUID workspaceId) {
        access.requireMember(session, workspaceId);
        return jdbc.query("""
                SELECT jsonb_build_object('serviceId',s.id,'slug',s.slug,'status',s.status,
                    'content',v.content_json)::text
                FROM catalog.service s LEFT JOIN catalog.service_version v
                  ON v.workspace_id=s.workspace_id AND v.id=COALESCE(s.current_version_id,
                    (SELECT v2.id FROM catalog.service_version v2 WHERE v2.workspace_id=s.workspace_id
                     AND v2.service_id=s.id ORDER BY v2.version_no DESC LIMIT 1))
                WHERE s.workspace_id=? ORDER BY s.slug
                """, (r, n) -> read(r.getString(1)), workspaceId);
    }

    @Transactional(readOnly = true)
    public JsonNode getService(String session, UUID workspaceId, UUID serviceId) {
        access.requireMember(session, workspaceId);
        return listServices(session, workspaceId).stream()
                .filter(node -> serviceId.toString().equals(node.path("serviceId").asText()))
                .findFirst().orElseThrow(CatalogIntakeService::notFound);
    }

    @Transactional(readOnly = true)
    public JsonNode getPublishedService(String slug) {
        JsonNode stored = jdbc.query("""
                SELECT v.content_json::text
                FROM catalog.service s JOIN catalog.service_version v
                  ON v.workspace_id=s.workspace_id AND v.id=s.current_version_id
                WHERE s.slug=? AND s.status='published' AND v.status='published'
                """, (r, n) -> read(r.getString(1)), slug).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
        ObjectNode response = json.createObjectNode();
        response.put("slug", slug);
        response.set("content", allowList(json, stored, PUBLIC_SERVICE_FIELDS));
        return response;
    }

    @Transactional(readOnly = true)
    public JsonNode getPublishedIntakeForm(String slug) {
        return jdbc.query("""
                SELECT jsonb_build_object('schema',f.schema_json,'privacyTextVersion',f.privacy_text_version)::text
                FROM catalog.service s JOIN catalog.intake_form_version f
                  ON f.workspace_id=s.workspace_id AND f.service_version_id=s.current_version_id
                WHERE s.slug=? AND s.status='published' AND f.status='published'
                """, (r, n) -> read(r.getString(1)), slug).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
    }

    @Transactional
    public Submission submitRequest(String serviceSlug, String email, String privacyVersion,
            boolean privacyAccepted, boolean botPassed, JsonNode answers) {
        requireObject(answers);
        PublishedIntake published = jdbc.query("""
                SELECT s.workspace_id,s.current_version_id,f.id,f.privacy_text_version,p.intake_status
                FROM catalog.service s JOIN catalog.intake_form_version f
                  ON f.workspace_id=s.workspace_id AND f.service_version_id=s.current_version_id
                JOIN catalog.creator_profile p ON p.workspace_id=s.workspace_id AND p.id=s.profile_id
                WHERE s.slug=? AND s.status='published' AND f.status='published'
                """, (r, n) -> new PublishedIntake(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class), r.getString(4), r.getString(5)), serviceSlug)
                .stream().findFirst().orElseThrow(CatalogIntakeService::notFound);
        byte[] emailDigest = identities.emailDigest(email);
        boolean blocked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM intake.sender_block "
                + "WHERE workspace_id=? AND subject_digest=? AND expires_at>?)", Boolean.class,
                published.workspaceId(), emailDigest, clock.instant());
        intake.requireAcceptable(published.workspaceId(), emailDigest, true,
                privacyAccepted && published.privacyVersion().equals(privacyVersion), botPassed,
                blocked, "open".equals(published.intakeStatus()), true);
        UUID requestId = UUID.randomUUID();
        requireOne(jdbc.update("""
                INSERT INTO intake.request(workspace_id,id,service_version_id,form_version_id,
                    requester_email_digest_v2,privacy_text_version,status,submitted_at)
                VALUES (?,?,?,?,?,?,'submitted',?)
                """, published.workspaceId(), requestId, published.serviceVersionId(), published.formVersionId(),
                emailDigest, privacyVersion, clock.instant()));
        UUID accessId = UUID.randomUUID();
        requireOne(jdbc.update("INSERT INTO intake.request_access(workspace_id,id,request_id,expires_at) "
                + "VALUES (?,?,?,?)", published.workspaceId(), accessId, requestId,
                clock.instant().plus(Duration.ofDays(30))));
        answers.properties().forEach(entry -> requireOne(jdbc.update("INSERT INTO intake.request_answer"
                + "(workspace_id,request_id,field_id,answer_json,sensitivity_class) VALUES (?,?,?,?::jsonb,'personal')",
                published.workspaceId(), requestId, entry.getKey(), write(entry.getValue()))));
        audit.recordAllowed("SubmitRequest", null, published.workspaceId());
        return new Submission(requestId, accessId);
    }

    @Transactional(readOnly = true)
    public List<JsonNode> listRequests(String session, UUID workspaceId) {
        access.requireMember(session, workspaceId);
        return jdbc.query("""
                SELECT jsonb_build_object('requestId',id,'status',status,'submittedAt',submitted_at)::text
                FROM intake.request WHERE workspace_id=? ORDER BY submitted_at DESC
                """, (r, n) -> read(r.getString(1)), workspaceId);
    }

    @Transactional
    public void classifyRequest(String session, UUID workspaceId, UUID requestId, String classification,
            String reason) {
        var actor = access.requireMember(session, workspaceId, EDITORS);
        if (!Set.of("screening", "clarification", "accepted", "declined").contains(classification)) invalid();
        requireOne(jdbc.update("UPDATE intake.request SET status=?,version=version+1 "
                + "WHERE workspace_id=? AND id=?", classification, workspaceId, requestId));
        requireOne(jdbc.update("INSERT INTO intake.screening_event"
                + "(workspace_id,request_id,id,classification,reason_code,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?)",
                workspaceId, requestId, UUID.randomUUID(), classification, reason, actor.accountId(), clock.instant()));
        audit.recordAllowed("ClassifyRequest", actor.accountId(), workspaceId);
    }

    @Transactional
    public void requestClarification(String session, UUID workspaceId, UUID requestId, String reason) {
        classifyRequest(session, workspaceId, requestId, "clarification", reason);
    }

    @Transactional
    public void withdrawRequest(UUID accessId) {
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE intake.request r SET status='withdrawn',version=version+1
                FROM intake.request_access a
                WHERE a.id=? AND a.workspace_id=r.workspace_id AND a.request_id=r.id
                  AND a.revoked_at IS NULL AND a.expires_at>? AND r.status NOT IN ('withdrawn','accepted','declined')
                """, accessId, now);
        requireOne(updated);
        requireOne(jdbc.update("UPDATE intake.request_access SET revoked_at=? WHERE id=? AND revoked_at IS NULL",
                now, accessId));
        audit.recordAllowed("WithdrawRequest", null, null);
    }

    @Transactional
    public void blockSender(String session, UUID workspaceId, UUID requestId, String reason, Instant expiresAt) {
        var actor = access.requireMember(session, workspaceId, PUBLISHERS);
        byte[] digest = jdbc.query("SELECT requester_email_digest_v2 FROM intake.request WHERE workspace_id=? AND id=?",
                (r, n) -> r.getBytes(1), workspaceId, requestId).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
        requireOne(jdbc.update("INSERT INTO intake.sender_block"
                + "(workspace_id,id,subject_digest,scope,reason_code,expires_at) VALUES (?,?,?,'workspace',?,?) "
                + "ON CONFLICT (workspace_id,subject_digest,scope) DO UPDATE "
                + "SET reason_code=excluded.reason_code,expires_at=excluded.expires_at",
                workspaceId, UUID.randomUUID(), digest, reason, expiresAt));
        audit.recordAllowed("BlockSender", actor.accountId(), workspaceId);
    }

    private ProfileRow profile(UUID workspaceId) {
        return jdbc.query("SELECT id,slug,draft_json::text,intake_status FROM catalog.creator_profile WHERE workspace_id=?",
                (r, n) -> new ProfileRow(r.getObject(1, UUID.class), r.getString(2), read(r.getString(3)),
                        r.getString(4)),
                workspaceId).stream().findFirst().orElseThrow(CatalogIntakeService::notFound);
    }

    private boolean hasPublishedWorkflow(UUID workspaceId) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.workflow_template_version "
                + "WHERE workspace_id=? AND status='published')", Boolean.class, workspaceId);
    }

    private ServiceDraft serviceDraft(UUID workspaceId, UUID serviceId) {
        return jdbc.query("""
                SELECT v.workflow_version_id,v.content_json::text,f.schema_json::text,f.privacy_text_version
                FROM catalog.service_version v JOIN catalog.intake_form_version f
                  ON f.workspace_id=v.workspace_id AND f.service_version_id=v.id
                WHERE v.workspace_id=? AND v.service_id=? ORDER BY v.version_no DESC LIMIT 1
                """, (r, n) -> new ServiceDraft(r.getObject(1, UUID.class), read(r.getString(2)),
                        read(r.getString(3)), r.getString(4)), workspaceId, serviceId).stream().findFirst()
                .orElseThrow(CatalogIntakeService::notFound);
    }

    private int nextVersion(String table, UUID workspaceId, String aggregateColumn, UUID aggregateId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM " + table
                + " WHERE workspace_id=? AND " + aggregateColumn + "=?", Integer.class, workspaceId, aggregateId);
    }

    private static void validateCheckpoints(List<CheckpointDraft> checkpoints) {
        if (checkpoints == null || checkpoints.isEmpty()) invalid();
        for (int index = 0; index < checkpoints.size(); index++) {
            CheckpointDraft item = checkpoints.get(index);
            if (item.sequence() != index + 1 || item.creatorDays() < 0 || item.clientDays() < 0
                    || item.type() == null || item.type().isBlank()) invalid();
            requireObject(item.policy());
        }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JacksonException failure) { throw new IllegalStateException("stored JSON is invalid", failure); }
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("JSON is invalid", failure); }
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) invalid();
    }

    private static void requireOne(int updated) {
        if (updated != 1) throw notFound();
    }

    private static ApiFailure notFound() { return ApiFailure.of(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }
    private static void invalid() { throw ApiFailure.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT"); }

    private void requirePublishable(boolean fieldsComplete, boolean workflowComplete) {
        try {
            publication.requirePublishable(fieldsComplete, workflowComplete, true, true);
        } catch (PublicationPolicy.CatalogRuleViolation failure) {
            HttpStatus status = switch (failure.reason()) {
                case FEATURE_WRITE_STOPPED -> HttpStatus.SERVICE_UNAVAILABLE;
                case ENTITLEMENT_DENIED -> HttpStatus.FORBIDDEN;
                case INCOMPLETE_DRAFT -> HttpStatus.CONFLICT;
            };
            throw ApiFailure.of(status, failure.reason().name());
        }
    }


    static ObjectNode publicProjection(ObjectMapper json, JsonNode draft) {
        return allowList(json, draft, PUBLIC_PROFILE_FIELDS);
    }

    static ObjectNode publicServiceProjection(ObjectMapper json, JsonNode draft) {
        return allowList(json, draft, PUBLIC_SERVICE_FIELDS);
    }

    private static ObjectNode allowList(ObjectMapper json, JsonNode draft, List<String> fields) {
        requireObject(draft);
        ObjectNode projection = json.createObjectNode();
        for (String field : fields) {
            JsonNode value = draft.get(field);
            if (value != null) projection.set(field, value);
        }
        return projection;
    }

    public record CheckpointDraft(int sequence, String type, int creatorDays, int clientDays, JsonNode policy) {}
    public record Submission(UUID requestId, UUID requestAccessId) {}
    private record ProfileRow(UUID id, String slug, JsonNode draft, String intakeStatus) {}
    private record WorkflowRow(UUID id, int version) {}
    private record ServiceDraft(UUID workflowVersionId, JsonNode content, JsonNode formSchema,
            String privacyVersion) {}
    private record PublishedIntake(UUID workspaceId, UUID serviceVersionId, UUID formVersionId,
            String privacyVersion, String intakeStatus) {}
}
