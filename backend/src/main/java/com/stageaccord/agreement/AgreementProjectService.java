package com.stageaccord.agreement;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.agreement.application.AgreementCanonicalizer;
import com.stageaccord.agreement.domain.AgreementAcceptancePolicy;
import com.stageaccord.identityaccess.api.AuthenticatedClient;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.api.ProtectedContact;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgreementProjectService {
    private static final String CLIENT_COOKIE = "__Host-stageaccord-client";
    private static final Set<WorkspaceAccess> MANAGERS = Set.of(
            WorkspaceAccess.OWNER, WorkspaceAccess.ADMIN, WorkspaceAccess.PROJECT_MANAGER);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkspaceAccessGateway workspaceAccess;
    private final IdentityAccessGateway identities;
    private final AuditRecorder audit;
    private final AgreementCanonicalizer canonicalizer = new AgreementCanonicalizer();
    private final AgreementAcceptancePolicy acceptance = new AgreementAcceptancePolicy();
    private final Clock clock;

    public AgreementProjectService(JdbcTemplate jdbc, ObjectMapper json,
            WorkspaceAccessGateway workspaceAccess, IdentityAccessGateway identities, AuditRecorder audit) {
        this(jdbc, json, workspaceAccess, identities, audit, Clock.systemUTC());
    }

    AgreementProjectService(JdbcTemplate jdbc, ObjectMapper json, WorkspaceAccessGateway workspaceAccess,
            IdentityAccessGateway identities, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.workspaceAccess = workspaceAccess;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID createOffer(String session, UUID workspaceId, UUID requestId, Instant expiresAt) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        UUID serviceVersionId = jdbc.query("SELECT service_version_id FROM intake.request "
                + "WHERE workspace_id=? AND id=? AND status='accepted' FOR UPDATE",
                (r, n) -> r.getObject(1, UUID.class), workspaceId, requestId).stream().findFirst()
                .orElseThrow(AgreementProjectService::conflict);
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO agreement.offer(workspace_id,id,request_id,service_version_id,status,expires_at) "
                + "VALUES (?,?,?,?,'draft',?)", workspaceId, id, requestId, serviceVersionId, expiresAt));
        audit.recordAllowed("CreateOffer", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional
    public OfferPublication offerAgreementVersion(String session, UUID workspaceId, UUID offerId,
            JsonNode agreement, String locale, String timeZone) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        OfferSource source = jdbc.query("""
                SELECT o.service_version_id,r.requester_email_ciphertext::text,
                       r.requester_email_digest_v2,v.workflow_version_id
                FROM agreement.offer o JOIN intake.request r
                  ON r.workspace_id=o.workspace_id AND r.id=o.request_id
                JOIN catalog.service_version v
                  ON v.workspace_id=o.workspace_id AND v.id=o.service_version_id
                WHERE o.workspace_id=? AND o.id=? AND o.status='draft' AND o.expires_at>? FOR UPDATE OF o
                """, (r, n) -> new OfferSource(r.getObject(1, UUID.class), r.getString(2), r.getBytes(3),
                        r.getObject(4, UUID.class)), workspaceId, offerId, clock.instant()).stream().findFirst()
                .orElseThrow(AgreementProjectService::conflict);
        if (source.protectedEmail() == null) throw conflict();
        var canonical = canonicalizer.canonicalize(write(agreement));
        UUID agreementId = UUID.randomUUID();
        one(jdbc.update("""
                INSERT INTO agreement.agreement_version(workspace_id,id,offer_id,version_no,canonical_json,
                    canonical_sha256,renderer_version,locale,time_zone,status)
                VALUES (?,?,?,1,?::jsonb,?,'jcs-v1',?,?,'offered')
                """, workspaceId, agreementId, offerId,
                new String(canonical.json(), StandardCharsets.UTF_8), canonical.sha256(), locale, timeZone));
        UUID creatorParty = actor.accountId();
        UUID clientParty = UUID.randomUUID();
        insertParty(workspaceId, agreementId, creatorParty, "creator", "issuer", new byte[32]);
        insertParty(workspaceId, agreementId, clientParty, "client", "approver", source.emailDigest());
        UUID projectId = UUID.randomUUID();
        one(jdbc.update("INSERT INTO project.project(workspace_id,id,agreement_version_id,status,waiting_on) "
                + "VALUES (?,?,?,'planned','CLIENT')", workspaceId, projectId, agreementId));
        one(jdbc.update("INSERT INTO project.workflow_instance(workspace_id,project_id,id,template_version_id,status) "
                + "VALUES (?,?,?,?,'planned')", workspaceId, projectId, UUID.randomUUID(), source.workflowVersionId()));
        createCheckpoints(workspaceId, projectId, source.workflowVersionId());
        UUID projectAccessId = UUID.randomUUID();
        one(jdbc.update("INSERT INTO project.client_access(workspace_id,id,project_id,party_id,expires_at) "
                + "VALUES (?,?,?,?,?)", workspaceId, projectAccessId, projectId, clientParty,
                clock.instant().plus(Duration.ofDays(30))));
        one(jdbc.update("UPDATE agreement.offer SET status='offered' WHERE workspace_id=? AND id=?",
                workspaceId, offerId));
        String email = identities.revealContact(read(source.protectedEmail(), ProtectedContact.class));
        identities.issueClientLink(workspaceId, projectId, email, "approver");
        audit.recordAllowed("OfferAgreementVersion", actor.accountId(), workspaceId);
        return new OfferPublication(agreementId, projectId, projectAccessId, canonical.sha256());
    }

    @Transactional(readOnly = true)
    public JsonNode getOfferedAgreement(String clientSession, UUID projectAccessId) {
        AuthenticatedClient client = requireClient(clientSession);
        return jdbc.query("""
                SELECT jsonb_build_object('agreementVersionId',a.id,'agreement',a.canonical_json,
                    'sha256',encode(a.canonical_sha256,'hex'),'locale',a.locale,'timeZone',a.time_zone)::text
                FROM project.client_access x JOIN project.project p
                  ON p.workspace_id=x.workspace_id AND p.id=x.project_id
                JOIN agreement.agreement_version a
                  ON a.workspace_id=p.workspace_id AND a.id=p.agreement_version_id
                WHERE x.id=? AND x.workspace_id=? AND x.project_id=? AND x.revoked_at IS NULL
                  AND x.expires_at>? AND a.status='offered'
                """, (r, n) -> read(r.getString(1)), projectAccessId, client.workspaceId(),
                client.projectId(), clock.instant()).stream().findFirst().orElseThrow(AgreementProjectService::notFound);
    }

    @Transactional
    public UUID acceptAgreementVersion(String clientSession, UUID agreementVersionId, String displayedHash) {
        AuthenticatedClient client = requireFreshClient(clientSession);
        AgreementTarget target = jdbc.query("""
                SELECT p.id,a.offer_id,a.canonical_sha256,x.party_id
                FROM project.project p JOIN agreement.agreement_version a
                  ON a.workspace_id=p.workspace_id AND a.id=p.agreement_version_id
                JOIN project.client_access x
                  ON x.workspace_id=p.workspace_id AND x.project_id=p.id
                WHERE p.workspace_id=? AND p.id=? AND a.id=? AND a.status='offered' FOR UPDATE OF p,a
                """, (r, n) -> new AgreementTarget(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getBytes(3), r.getObject(4, UUID.class)), client.workspaceId(), client.projectId(),
                agreementVersionId).stream().findFirst().orElseThrow(AgreementProjectService::conflict);
        byte[] displayed = java.util.HexFormat.of().parseHex(displayedHash);
        try {
            acceptance.requireAcceptable(displayed, target.hash(), true, client.authenticatedAt(),
                    clock.instant(), true, true);
        } catch (AgreementAcceptancePolicy.AgreementRuleViolation failure) {
            throw ApiFailure.of(HttpStatus.CONFLICT, failure.reason().name());
        }
        one(jdbc.update("INSERT INTO agreement.party_acceptance(workspace_id,agreement_version_id,party_id,"
                + "party_role,action,authenticated_at,accepted_at,target_hash) VALUES (?,?,?,'approver','accepted',?,?,?)",
                client.workspaceId(), agreementVersionId, target.partyId(), client.authenticatedAt(),
                clock.instant(), target.hash()));
        one(jdbc.update("UPDATE agreement.agreement_version SET status='accepted' WHERE workspace_id=? AND id=?",
                client.workspaceId(), agreementVersionId));
        one(jdbc.update("UPDATE agreement.offer SET status='accepted' WHERE workspace_id=? AND id=?",
                client.workspaceId(), target.offerId()));
        UUID first = jdbc.query("SELECT id FROM project.checkpoint_instance WHERE workspace_id=? AND project_id=? "
                + "ORDER BY sequence_no LIMIT 1", (r, n) -> r.getObject(1, UUID.class),
                client.workspaceId(), target.projectId()).stream().findFirst().orElseThrow(AgreementProjectService::conflict);
        one(jdbc.update("UPDATE project.checkpoint_instance SET status='active' WHERE workspace_id=? AND id=?",
                client.workspaceId(), first));
        one(jdbc.update("UPDATE project.project SET status='active',waiting_on='CREATOR',current_checkpoint_id=?,"
                + "version=version+1 WHERE workspace_id=? AND id=?", first, client.workspaceId(), target.projectId()));
        audit.recordAllowed("AcceptAgreementVersion", null, client.workspaceId());
        return target.projectId();
    }

    @Transactional(readOnly = true)
    public List<JsonNode> listProjects(String session, UUID workspaceId) {
        workspaceAccess.requireMember(session, workspaceId);
        return jdbc.query("SELECT jsonb_build_object('projectId',id,'status',status,'waitingOn',waiting_on,"
                + "'currentCheckpointId',current_checkpoint_id,'version',version)::text FROM project.project "
                + "WHERE workspace_id=? ORDER BY id", (r, n) -> read(r.getString(1)), workspaceId);
    }

    @Transactional(readOnly = true)
    public JsonNode getCreatorProject(String session, UUID workspaceId, UUID projectId) {
        workspaceAccess.requireMember(session, workspaceId);
        return project(workspaceId, projectId);
    }

    @Transactional(readOnly = true)
    public JsonNode getClientProject(String clientSession, UUID projectAccessId) {
        AuthenticatedClient client = requireProjectAccess(clientSession, projectAccessId);
        return project(client.workspaceId(), client.projectId());
    }

    @Transactional
    public UUID openClientDispute(String clientSession, UUID projectAccessId, String reason) {
        AuthenticatedClient client = requireProjectAccess(clientSession, projectAccessId);
        return openDispute(clientSession, client.workspaceId(), client.projectId(), reason, true);
    }

    @Transactional
    public UUID requestClientCancellation(String clientSession, UUID projectAccessId, String reason) {
        AuthenticatedClient client = requireProjectAccess(clientSession, projectAccessId);
        return requestCancellation(clientSession, client.workspaceId(), client.projectId(), reason, true);
    }

    @Transactional
    public void confirmClientCancellation(String clientSession, UUID cancellationId) {
        AuthenticatedClient client = requireClient(clientSession);
        confirmCancellation(clientSession, client.workspaceId(), client.projectId(), cancellationId, true);
    }

    @Transactional
    public void resolveClientDispute(String clientSession, UUID disputeId, String resolution) {
        AuthenticatedClient client = requireClient(clientSession);
        resolveDispute(clientSession, client.workspaceId(), client.projectId(), disputeId, resolution, true);
    }

    @Transactional
    public UUID recordClientExternalPayment(String clientSession, UUID projectAccessId, String provider,
            String reference, long amountMinor, String currency, String status) {
        AuthenticatedClient client = requireProjectAccess(clientSession, projectAccessId);
        return recordExternalPayment(clientSession, client.workspaceId(), client.projectId(), provider,
                reference, amountMinor, currency, status, true);
    }

    @Transactional
    public void changeProjectStatus(String session, UUID workspaceId, UUID projectId,
            String expected, String next, String operation) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        one(jdbc.update("UPDATE project.project SET status=?,version=version+1 WHERE workspace_id=? AND id=? AND status=?",
                next, workspaceId, projectId, expected));
        audit.recordAllowed(operation, actor.accountId(), workspaceId);
    }

    @Transactional
    public UUID openDispute(String session, UUID workspaceId, UUID projectId, String reason, boolean clientSide) {
        UUID actorId;
        if (clientSide) {
            AuthenticatedClient client = requireClient(session);
            if (!client.workspaceId().equals(workspaceId) || !client.projectId().equals(projectId)) throw notFound();
            actorId = client.sessionId();
        } else actorId = workspaceAccess.requireMember(session, workspaceId, MANAGERS).accountId();
        String prior = jdbc.query("SELECT status FROM project.project WHERE workspace_id=? AND id=? FOR UPDATE",
                (r, n) -> r.getString(1), workspaceId, projectId).stream().findFirst().orElseThrow(AgreementProjectService::notFound);
        if (!List.of("active", "on_hold").contains(prior)) throw conflict();
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO project.dispute(workspace_id,project_id,id,opened_by,reason,prior_state,status) "
                + "VALUES (?,?,?,?,?,?,'open')", workspaceId, projectId, id, actorId, reason, prior));
        one(jdbc.update("UPDATE project.project SET status='disputed',version=version+1 WHERE workspace_id=? AND id=?",
                workspaceId, projectId));
        audit.recordAllowed(clientSide ? "OpenClientDispute" : "OpenCreatorDispute", actorId, workspaceId);
        return id;
    }

    @Transactional
    public void assignMember(String session, UUID workspaceId, UUID projectId, UUID memberId,
            String scopeType, UUID scopeId) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        one(jdbc.update("INSERT INTO project.assignment(workspace_id,project_id,id,member_id,scope_type,scope_id,status) "
                + "VALUES (?,?,?,?,?,?,'active') ON CONFLICT (workspace_id,project_id,member_id,scope_type,scope_id) "
                + "DO UPDATE SET status='active'", workspaceId, projectId, UUID.randomUUID(), memberId, scopeType, scopeId));
        audit.recordAllowed("AssignMember", actor.accountId(), workspaceId);
    }

    @Transactional
    public void submitCheckpoint(String session, UUID workspaceId, UUID checkpointId) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        boolean ready = jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM project.checkpoint_item "
                + "WHERE workspace_id=? AND checkpoint_id=? AND required AND status<>'ready')",
                Boolean.class, workspaceId, checkpointId);
        if (!ready) throw ApiFailure.of(HttpStatus.CONFLICT, "REQUIRED_ITEM_MISSING");
        one(jdbc.update("UPDATE project.checkpoint_instance SET status='submitted',version=version+1 "
                + "WHERE workspace_id=? AND id=? AND status='active'", workspaceId, checkpointId));
        jdbc.update("UPDATE project.project SET waiting_on='CLIENT',version=version+1 "
                + "WHERE workspace_id=? AND current_checkpoint_id=?", workspaceId, checkpointId);
        audit.recordAllowed("SubmitCheckpoint", actor.accountId(), workspaceId);
    }

    @Transactional
    public void completeClientInput(String clientSession, UUID checkpointId) {
        AuthenticatedClient client = requireClient(clientSession);
        one(jdbc.update("UPDATE project.checkpoint_instance SET status='completed',version=version+1 "
                + "WHERE workspace_id=? AND project_id=? AND id=? AND status='active' AND type='CLIENT_INPUT'",
                client.workspaceId(), client.projectId(), checkpointId));
        advanceCheckpoint(client.workspaceId(), client.projectId(), checkpointId);
        audit.recordAllowed("CompleteClientInput", null, client.workspaceId());
    }

    @Transactional
    public UUID proposeChangeOrder(String session, UUID workspaceId, UUID projectId, JsonNode diff,
            String reason, JsonNode scheduleImpact) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        UUID agreementVersionId = jdbc.query("SELECT agreement_version_id FROM project.project "
                + "WHERE workspace_id=? AND id=? AND status IN ('active','on_hold') FOR UPDATE",
                (r, n) -> r.getObject(1, UUID.class), workspaceId, projectId).stream().findFirst()
                .orElseThrow(AgreementProjectService::conflict);
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO agreement.change_order(workspace_id,id,project_id,base_agreement_version_id,"
                + "diff_json,reason,schedule_impact_json,status) VALUES (?,?,?,?,?::jsonb,?,?::jsonb,'proposed')",
                workspaceId, id, projectId, agreementVersionId, write(diff), reason, write(scheduleImpact)));
        audit.recordAllowed("ProposeChangeOrder", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional
    public void acceptChangeOrder(String clientSession, UUID changeOrderId) {
        AuthenticatedClient client = requireFreshClient(clientSession);
        one(jdbc.update("UPDATE agreement.change_order SET status='accepted' WHERE workspace_id=? AND project_id=? "
                + "AND id=? AND status='proposed'", client.workspaceId(), client.projectId(), changeOrderId));
        audit.recordAllowed("AcceptChangeOrder", null, client.workspaceId());
    }

    @Transactional
    public UUID saveBusinessCalendar(String session, UUID workspaceId, String timeZone,
            JsonNode workdays, LocalTime cutoff, List<CalendarException> exceptions) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        UUID id = jdbc.query("SELECT id FROM schedule.business_calendar WHERE workspace_id=? FOR UPDATE",
                (r, n) -> r.getObject(1, UUID.class), workspaceId).stream().findFirst().orElse(UUID.randomUUID());
        jdbc.update("INSERT INTO schedule.business_calendar(workspace_id,id,time_zone,workdays,cutoff_local_time) "
                + "VALUES (?,?,?,?::jsonb,?) ON CONFLICT (workspace_id,id) DO UPDATE SET time_zone=EXCLUDED.time_zone,"
                + "workdays=EXCLUDED.workdays,cutoff_local_time=EXCLUDED.cutoff_local_time,version=schedule.business_calendar.version+1",
                workspaceId, id, timeZone, write(workdays), cutoff);
        jdbc.update("DELETE FROM schedule.calendar_exception WHERE workspace_id=? AND calendar_id=?", workspaceId, id);
        for (CalendarException exception : exceptions) {
            one(jdbc.update("INSERT INTO schedule.calendar_exception(workspace_id,calendar_id,local_date,kind,label) "
                    + "VALUES (?,?,?,?,?)", workspaceId, id, exception.date(), exception.kind(), exception.label()));
        }
        audit.recordAllowed("SaveBusinessCalendar", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional
    public UUID proposeScheduleChange(String session, UUID workspaceId, UUID projectId, String reason,
            long baseVersion, JsonNode changes, boolean finalDeliveryChanged, List<ScheduleItem> items) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        boolean matches = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM project.project WHERE workspace_id=? "
                + "AND id=? AND version=? AND status IN ('active','on_hold'))", Boolean.class,
                workspaceId, projectId, baseVersion);
        if (!matches || items.isEmpty()) throw conflict();
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO schedule.schedule_proposal(workspace_id,project_id,id,reason,base_version,"
                + "changes_json,final_delivery_changed,status) VALUES (?,?,?,?,?,?::jsonb,?,'proposed')",
                workspaceId, projectId, id, reason, baseVersion, write(changes), finalDeliveryChanged));
        for (ScheduleItem item : items) {
            one(jdbc.update("INSERT INTO schedule.schedule_item(workspace_id,proposal_id,checkpoint_id,old_due_at,"
                    + "new_due_at,reason_code) SELECT ?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM project.checkpoint_instance "
                    + "WHERE workspace_id=? AND project_id=? AND id=? AND creator_due_at=?)",
                    workspaceId, id, item.checkpointId(), item.oldDueAt(), item.newDueAt(), item.reasonCode(),
                    workspaceId, projectId, item.checkpointId(), item.oldDueAt()));
        }
        audit.recordAllowed("ProposeScheduleChange", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional
    public void acceptScheduleChange(String clientSession, UUID proposalId) {
        AuthenticatedClient client = requireFreshClient(clientSession);
        long base = jdbc.query("SELECT base_version FROM schedule.schedule_proposal WHERE workspace_id=? "
                + "AND project_id=? AND id=? AND status='proposed' FOR UPDATE",
                (r, n) -> r.getLong(1), client.workspaceId(), client.projectId(), proposalId).stream()
                .findFirst().orElseThrow(AgreementProjectService::conflict);
        one(jdbc.update("UPDATE project.project SET version=version+1 WHERE workspace_id=? AND id=? AND version=?",
                client.workspaceId(), client.projectId(), base));
        jdbc.update("UPDATE project.checkpoint_instance c SET creator_due_at=i.new_due_at,client_due_at=i.new_due_at "
                + "FROM schedule.schedule_item i WHERE i.workspace_id=? AND i.proposal_id=? "
                + "AND c.workspace_id=i.workspace_id AND c.id=i.checkpoint_id", client.workspaceId(), proposalId);
        one(jdbc.update("UPDATE schedule.schedule_proposal SET status='accepted' WHERE workspace_id=? AND id=?",
                client.workspaceId(), proposalId));
        audit.recordAllowed("AcceptScheduleChange", null, client.workspaceId());
    }

    @Transactional
    public UUID createInternalTask(String session, UUID workspaceId, UUID projectId, UUID checkpointId,
            String title, String priority, Instant dueAt, UUID assigneeId) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO project.internal_task(workspace_id,project_id,checkpoint_id,id,title,priority,"
                + "due_at,status,assignee_id) SELECT ?,?,?,?,?,?,?,'open',? WHERE EXISTS(SELECT 1 FROM "
                + "project.checkpoint_instance WHERE workspace_id=? AND project_id=? AND id=?)",
                workspaceId, projectId, checkpointId, id, title, priority, dueAt, assigneeId,
                workspaceId, projectId, checkpointId));
        audit.recordAllowed("CreateInternalTask", actor.accountId(), workspaceId);
        return id;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> listInternalTasks(String session, UUID workspaceId, UUID projectId) {
        workspaceAccess.requireMember(session, workspaceId);
        return jdbc.query("SELECT jsonb_build_object('id',id,'checkpointId',checkpoint_id,'title',title,"
                + "'priority',priority,'dueAt',due_at,'status',status,'assigneeId',assignee_id,'version',version)::text "
                + "FROM project.internal_task WHERE workspace_id=? AND project_id=? ORDER BY due_at,id",
                (r, n) -> read(r.getString(1)), workspaceId, projectId);
    }

    @Transactional
    public void updateInternalTask(String session, UUID workspaceId, UUID projectId, UUID taskId,
            String status, long expectedVersion) {
        var actor = workspaceAccess.requireMember(session, workspaceId, MANAGERS);
        one(jdbc.update("UPDATE project.internal_task SET status=?,version=version+1 WHERE workspace_id=? "
                + "AND project_id=? AND id=? AND version=?", status, workspaceId, projectId, taskId, expectedVersion));
        audit.recordAllowed("UpdateInternalTask", actor.accountId(), workspaceId);
    }

    @Transactional
    public UUID requestCancellation(String session, UUID workspaceId, UUID projectId, String reason,
            boolean clientSide) {
        UUID actorId;
        if (clientSide) {
            AuthenticatedClient client = requireClient(session);
            if (!client.workspaceId().equals(workspaceId) || !client.projectId().equals(projectId)) throw notFound();
            actorId = client.sessionId();
        } else actorId = workspaceAccess.requireMember(session, workspaceId, MANAGERS).accountId();
        one(jdbc.update("UPDATE project.project SET status='cancelling',version=version+1 WHERE workspace_id=? "
                + "AND id=? AND status IN ('active','on_hold','disputed')", workspaceId, projectId));
        UUID id = UUID.randomUUID();
        one(jdbc.update("INSERT INTO project.cancellation(workspace_id,project_id,id,requested_by,reason,status) "
                + "VALUES (?,?,?,?,?,'confirming')", workspaceId, projectId, id, actorId, reason));
        UUID creator = jdbc.query("SELECT party_id FROM agreement.agreement_party a JOIN project.project p "
                + "ON p.workspace_id=a.workspace_id AND p.agreement_version_id=a.agreement_version_id "
                + "WHERE p.workspace_id=? AND p.id=? AND a.party_type='creator'", (r,n)->r.getObject(1,UUID.class),
                workspaceId, projectId).stream().findFirst().orElseThrow(AgreementProjectService::conflict);
        UUID client = jdbc.query("SELECT party_id FROM project.client_access WHERE workspace_id=? AND project_id=?",
                (r,n)->r.getObject(1,UUID.class), workspaceId, projectId).stream().findFirst()
                .orElseThrow(AgreementProjectService::conflict);
        for (UUID party : List.of(creator, client)) one(jdbc.update("INSERT INTO project.cancellation_required_party"
                + "(workspace_id,cancellation_id,party_id,required_action) VALUES (?,?,?,'confirm')",
                workspaceId, id, party));
        audit.recordAllowed(clientSide ? "RequestClientProjectCancellation" : "RequestCreatorProjectCancellation",
                actorId, workspaceId);
        return id;
    }

    @Transactional
    public void confirmCancellation(String session, UUID workspaceId, UUID projectId, UUID cancellationId,
            boolean clientSide) {
        UUID partyId;
        if (clientSide) {
            AuthenticatedClient client = requireFreshClient(session);
            if (!client.workspaceId().equals(workspaceId) || !client.projectId().equals(projectId)) throw notFound();
            partyId = jdbc.query("SELECT party_id FROM project.client_access WHERE workspace_id=? AND project_id=?",
                    (r,n)->r.getObject(1,UUID.class), workspaceId, projectId).stream().findFirst()
                    .orElseThrow(AgreementProjectService::notFound);
        } else partyId = workspaceAccess.requireMember(session, workspaceId, MANAGERS).accountId();
        one(jdbc.update("UPDATE project.cancellation_required_party SET confirmed_at=? WHERE workspace_id=? "
                + "AND cancellation_id=? AND party_id=? AND confirmed_at IS NULL", clock.instant(), workspaceId,
                cancellationId, partyId));
        boolean complete = jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM project.cancellation_required_party "
                + "WHERE workspace_id=? AND cancellation_id=? AND confirmed_at IS NULL)", Boolean.class,
                workspaceId, cancellationId);
        if (complete) {
            one(jdbc.update("UPDATE project.cancellation SET status='cancelled' WHERE workspace_id=? AND project_id=? "
                    + "AND id=? AND status='confirming'", workspaceId, projectId, cancellationId));
            one(jdbc.update("UPDATE project.project SET status='cancelled',waiting_on='NONE',version=version+1 "
                    + "WHERE workspace_id=? AND id=? AND status='cancelling'", workspaceId, projectId));
        }
        audit.recordAllowed(clientSide ? "ConfirmClientProjectCancellation" : "ConfirmCreatorProjectCancellation",
                partyId, workspaceId);
    }

    @Transactional
    public void resolveDispute(String session, UUID workspaceId, UUID projectId, UUID disputeId,
            String resolution, boolean clientSide) {
        UUID actorId;
        if (clientSide) {
            AuthenticatedClient client = requireFreshClient(session);
            if (!client.workspaceId().equals(workspaceId) || !client.projectId().equals(projectId)) throw notFound();
            actorId = client.sessionId();
        } else actorId = workspaceAccess.requireMember(session, workspaceId, MANAGERS).accountId();
        String prior = jdbc.query("SELECT prior_state FROM project.dispute WHERE workspace_id=? AND project_id=? "
                + "AND id=? AND status='open' FOR UPDATE", (r,n)->r.getString(1), workspaceId, projectId, disputeId)
                .stream().findFirst().orElseThrow(AgreementProjectService::conflict);
        if (!"accepted".equals(resolution)) throw ApiFailure.of(HttpStatus.CONFLICT, "RESOLUTION_NOT_ACCEPTED");
        one(jdbc.update("UPDATE project.dispute SET status='resolved',resolved_at=? WHERE workspace_id=? AND id=?",
                clock.instant(), workspaceId, disputeId));
        one(jdbc.update("UPDATE project.project SET status=?,version=version+1 WHERE workspace_id=? AND id=? "
                + "AND status='disputed'", prior, workspaceId, projectId));
        audit.recordAllowed(clientSide ? "RecordClientDisputeResolution" : "RecordCreatorDisputeResolution",
                actorId, workspaceId);
    }

    @Transactional
    public UUID recordExternalPayment(String session, UUID workspaceId, UUID projectId, String provider,
            String reference, long amountMinor, String currency, String status, boolean clientSide) {
        UUID actorId;
        if (clientSide) {
            AuthenticatedClient client = requireClient(session);
            if (!client.workspaceId().equals(workspaceId) || !client.projectId().equals(projectId)) throw notFound();
            actorId = client.sessionId();
        } else actorId = workspaceAccess.requireMember(session, workspaceId, MANAGERS).accountId();
        UUID id = UUID.randomUUID();
        JsonNode referencePayload = json.createObjectNode().put("provider", provider).put("reference", reference)
                .put("amountMinor", amountMinor).put("currency", currency);
        ProtectedContact protectedReference = identities.protectContact(write(referencePayload));
        one(jdbc.update("INSERT INTO project.external_payment_record(workspace_id,project_id,id,status,"
                + "reference_ciphertext,recorded_by,recorded_at) VALUES (?,?,?,?,?::jsonb,?,?)", workspaceId,
                projectId, id, status, write(protectedReference), actorId, clock.instant()));
        audit.recordAllowed(clientSide ? "RecordClientExternalPaymentStatus" : "RecordCreatorExternalPaymentStatus",
                actorId, workspaceId);
        return id;
    }

    private void createCheckpoints(UUID workspaceId, UUID projectId, UUID templateVersionId) {
        Instant now = clock.instant();
        List<CheckpointSeed> seeds = jdbc.query("SELECT sequence_no,type,duration_days,client_due_days "
                + "FROM catalog.checkpoint_template WHERE workspace_id=? AND workflow_version_id=? ORDER BY sequence_no",
                (r, n) -> new CheckpointSeed(r.getInt(1), r.getString(2), r.getInt(3), r.getInt(4)),
                workspaceId, templateVersionId);
        if (seeds.isEmpty()) throw conflict();
        for (CheckpointSeed seed : seeds) {
            one(jdbc.update("INSERT INTO project.checkpoint_instance(workspace_id,project_id,id,sequence_no,type,status,"
                    + "creator_due_at,client_due_at) VALUES (?,?,?,?,?,'locked',?,?)",
                    workspaceId, projectId, UUID.randomUUID(), seed.sequence(), seed.type(),
                    now.plus(Duration.ofDays(seed.creatorDays())), now.plus(Duration.ofDays(seed.clientDays()))));
        }
    }

    private void advanceCheckpoint(UUID workspaceId, UUID projectId, UUID checkpointId) {
        Integer sequence = jdbc.queryForObject("SELECT sequence_no FROM project.checkpoint_instance "
                + "WHERE workspace_id=? AND project_id=? AND id=?", Integer.class, workspaceId, projectId, checkpointId);
        UUID next = jdbc.query("SELECT id FROM project.checkpoint_instance WHERE workspace_id=? AND project_id=? "
                + "AND sequence_no=?", (r, n) -> r.getObject(1, UUID.class), workspaceId, projectId, sequence + 1)
                .stream().findFirst().orElse(null);
        if (next == null) {
            jdbc.update("UPDATE project.project SET status='completed',waiting_on='NONE',current_checkpoint_id=NULL,"
                    + "version=version+1 WHERE workspace_id=? AND id=?", workspaceId, projectId);
        } else {
            jdbc.update("UPDATE project.checkpoint_instance SET status='active' WHERE workspace_id=? AND id=?",
                    workspaceId, next);
            jdbc.update("UPDATE project.project SET waiting_on='CREATOR',current_checkpoint_id=?,version=version+1 "
                    + "WHERE workspace_id=? AND id=?", next, workspaceId, projectId);
        }
    }

    private JsonNode project(UUID workspaceId, UUID projectId) {
        return jdbc.query("SELECT jsonb_build_object('projectId',id,'status',status,'waitingOn',waiting_on,"
                + "'currentCheckpointId',current_checkpoint_id,'version',version)::text FROM project.project "
                + "WHERE workspace_id=? AND id=?", (r, n) -> read(r.getString(1)), workspaceId, projectId)
                .stream().findFirst().orElseThrow(AgreementProjectService::notFound);
    }

    private AuthenticatedClient requireClient(String token) { return identities.resolveClient(token); }
    private AuthenticatedClient requireProjectAccess(String token, UUID projectAccessId) {
        AuthenticatedClient client = requireClient(token);
        boolean allowed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM project.client_access "
                + "WHERE id=? AND workspace_id=? AND project_id=? AND revoked_at IS NULL AND expires_at>?)",
                Boolean.class, projectAccessId, client.workspaceId(), client.projectId(), clock.instant());
        if (!allowed) throw notFound();
        return client;
    }
    private AuthenticatedClient requireFreshClient(String token) {
        AuthenticatedClient client = requireClient(token);
        if (Duration.between(client.authenticatedAt(), clock.instant()).isNegative()
                || Duration.between(client.authenticatedAt(), clock.instant()).compareTo(Duration.ofMinutes(30)) > 0) {
            throw ApiFailure.of(HttpStatus.UNAUTHORIZED, "AUTH_FRESHNESS_REQUIRED");
        }
        return client;
    }

    private void insertParty(UUID workspaceId, UUID agreementId, UUID partyId, String type, String role, byte[] digest) {
        byte[] value = digest.length == 32 ? digest : new byte[32];
        one(jdbc.update("INSERT INTO agreement.agreement_party(workspace_id,agreement_version_id,party_id,party_type,"
                + "role,email_digest_v2,eligible) VALUES (?,?,?,?,?,?,true)",
                workspaceId, agreementId, partyId, type, role, value));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw ApiFailure.of(HttpStatus.BAD_REQUEST, "INVALID_INPUT"); }
    }
    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JacksonException failure) { throw new IllegalStateException("stored JSON is invalid", failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored protected value is invalid", failure); }
    }
    private static void one(int count) { if (count != 1) throw conflict(); }
    private static ApiFailure notFound() { return ApiFailure.of(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }
    private static ApiFailure conflict() { return ApiFailure.of(HttpStatus.CONFLICT, "STATE_CONFLICT"); }

    public record OfferPublication(UUID agreementVersionId, UUID projectId, UUID projectAccessId, byte[] hash) {
        public OfferPublication { hash = hash.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
    private record OfferSource(UUID serviceVersionId, String protectedEmail, byte[] emailDigest,
            UUID workflowVersionId) {}
    private record AgreementTarget(UUID projectId, UUID offerId, byte[] hash, UUID partyId) {}
    private record CheckpointSeed(int sequence, String type, int creatorDays, int clientDays) {}
    public record CalendarException(LocalDate date, String kind, String label) {}
    public record ScheduleItem(UUID checkpointId, Instant oldDueAt, Instant newDueAt, String reasonCode) {}
}
