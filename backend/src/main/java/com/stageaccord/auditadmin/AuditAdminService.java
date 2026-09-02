package com.stageaccord.auditadmin;

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

import com.stageaccord.identityaccess.api.AuthenticatedClient;
import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditAdminService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final IdentityAccessGateway identities;
    private final AuditRecorder audit;private final Clock clock=Clock.systemUTC();
    public AuditAdminService(JdbcTemplate jdbc,ObjectMapper json,IdentityAccessGateway identities,AuditRecorder audit){
        this.jdbc=jdbc;this.json=json;this.identities=identities;this.audit=audit;}
    @Transactional public UUID requestSupportGrant(String session,UUID workspaceId,UUID projectId,String ticketId,
            String purpose,JsonNode operations){Operator requester=requireOperator(session,Set.of("support","administrator"),true);UUID id=UUID.randomUUID();
        one(jdbc.update("INSERT INTO audit.support_request(id,workspace_id,project_id,ticket_id,purpose,allowed_operations,"
                +"requester_id,status,requested_at) VALUES (?,?,?,?,?,?::jsonb,?,'requested',?)",id,workspaceId,projectId,ticketId,purpose,
                write(operations),requester.accountId(),clock.instant()));audit.recordAllowed("RequestSupportGrant",requester.accountId(),workspaceId);return id;}
    @Transactional public UUID approveSupportGrant(String session,UUID requestId,Instant expiresAt){Operator approver=requireOperator(session,
            Set.of("security","administrator"),true);Request request=jdbc.query("SELECT workspace_id,project_id,ticket_id,purpose,allowed_operations::text,"
                +"requester_id FROM audit.support_request WHERE id=? AND status='requested' FOR UPDATE",(r,n)->new Request(r.getObject(1,UUID.class),
                r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class)),requestId).stream().findFirst()
                .orElseThrow(AuditAdminService::notFound);if(request.requester().equals(approver.accountId())||!expiresAt.isAfter(clock.instant())||
                expiresAt.isAfter(clock.instant().plus(Duration.ofMinutes(60))))throw conflict("SEPARATION_OR_EXPIRY_INVALID");UUID id=UUID.randomUUID();
        one(jdbc.update("INSERT INTO audit.support_grant(workspace_id,project_id,id,ticket_id,purpose,allowed_operations,requester_id,"
                +"approver_id,approved_at,expires_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?)",request.workspaceId(),request.projectId(),id,request.ticket(),
                request.purpose(),request.operations(),request.requester(),approver.accountId(),clock.instant(),expiresAt));one(jdbc.update("UPDATE "
                +"audit.support_request SET status='approved',decided_at=? WHERE id=? AND status='requested'",clock.instant(),requestId));
        audit.recordAllowed("ApproveSupportGrant",approver.accountId(),request.workspaceId());return id;}
    @Transactional public UUID activateKillSwitch(String session,UUID workspaceId,String feature,String reason,String releaseCondition){Operator actor=
        requireOperator(session,Set.of("security","administrator"),true);if(stopped(workspaceId,feature))throw conflict("KILL_SWITCH_ALREADY_ACTIVE");
        UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO audit.kill_switch_event(workspace_id,id,feature,action,reason,initiated_by,authenticated_at,"
                +"occurred_at,release_condition) VALUES (?,?,?,'stopped',?,?,?,?,?)",workspaceId,id,feature,reason,actor.accountId(),actor.authenticatedAt(),
                clock.instant(),releaseCondition));audit.recordAllowed("ActivateKillSwitch",actor.accountId(),workspaceId);return id;}
    @Transactional public UUID releaseKillSwitch(String session,UUID workspaceId,String feature,String reason,String releaseCondition){Operator actor=
        requireOperator(session,Set.of("security","administrator"),true);UUID initiator=jdbc.query("SELECT initiated_by FROM audit.kill_switch_event WHERE "
                +"workspace_id=? AND feature=? ORDER BY occurred_at DESC LIMIT 1",(r,n)->r.getObject(1,UUID.class),workspaceId,feature).stream().findFirst()
                .orElseThrow(AuditAdminService::notFound);if(!stopped(workspaceId,feature)||initiator.equals(actor.accountId()))throw conflict("SECOND_OPERATOR_REQUIRED");
        UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO audit.kill_switch_event(workspace_id,id,feature,action,reason,initiated_by,confirmed_by,"
                +"authenticated_at,occurred_at,release_condition) VALUES (?,?,?,'released',?,?,?,?,?,?)",workspaceId,id,feature,reason,initiator,
                actor.accountId(),actor.authenticatedAt(),clock.instant(),releaseCondition));audit.recordAllowed("ReleaseKillSwitch",actor.accountId(),workspaceId);return id;}
    @Transactional public UUID submitReport(String creatorSession,String clientSession,UUID workspaceId,String subjectType,UUID subjectId,
            String reasonCode,String detail){UUID reporter;if(creatorSession!=null)reporter=identities.resolve(creatorSession).accountId();else{
            AuthenticatedClient client=identities.resolveClient(clientSession);if(!client.workspaceId().equals(workspaceId))throw notFound();reporter=client.sessionId();}
        UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO audit.report(workspace_id,id,reporter_id,subject_type,subject_id,reason_code,detail_ciphertext,"
                +"status,created_at) VALUES (?,?,?,?,?,?,?::jsonb,'open',?)",workspaceId,id,reporter,subjectType,subjectId,reasonCode,
                write(identities.protectContact(detail)),clock.instant()));audit.recordAllowed("SubmitReport",reporter,workspaceId);return id;}
    @Transactional(readOnly=true) public List<JsonNode> listReports(String session){requireOperator(session,Set.of("support","security","administrator"),false);
        return jdbc.query("SELECT jsonb_build_object('id',id,'workspaceId',workspace_id,'subjectType',subject_type,'subjectId',subject_id,"
                +"'reasonCode',reason_code,'status',status,'createdAt',created_at,'resolvedAt',resolved_at)::text FROM audit.report ORDER BY created_at DESC",
                (r,n)->read(r.getString(1)));}
    @Transactional public void updateReportDisposition(String session,UUID reportId,String status){Operator actor=requireOperator(session,
            Set.of("support","security","administrator"),false);if(!List.of("investigating","resolved","dismissed").contains(status))throw conflict("INVALID_STATUS");
        Instant resolved=List.of("resolved","dismissed").contains(status)?clock.instant():null;UUID workspace=jdbc.query("UPDATE audit.report SET status=?,resolved_at=? "
                +"WHERE id=? AND status IN ('open','investigating') RETURNING workspace_id",(r,n)->r.getObject(1,UUID.class),status,resolved,reportId).stream()
                .findFirst().orElseThrow(AuditAdminService::conflict);audit.recordAllowed("UpdateReportDisposition",actor.accountId(),workspace);}
    private Operator requireOperator(String session,Set<String> roles,boolean fresh){AuthenticatedPrincipal principal=identities.resolve(session);if(fresh&&
            !principal.isFresh(clock.instant()))throw ApiFailure.of(HttpStatus.UNAUTHORIZED,"AUTH_FRESHNESS_REQUIRED");return jdbc.query("SELECT role FROM audit.operator "
                +"WHERE account_id=? AND status='active'",(r,n)->new Operator(principal.accountId(),r.getString(1),principal.authenticatedAt()),principal.accountId()).stream()
                .filter(o->roles.contains(o.role())).findFirst().orElseThrow(()->ApiFailure.of(HttpStatus.FORBIDDEN,"OPERATOR_ACCESS_REQUIRED"));}
    private boolean stopped(UUID workspace,String feature){return jdbc.query("SELECT action FROM audit.kill_switch_event WHERE workspace_id=? AND feature=? "
                +"ORDER BY occurred_at DESC LIMIT 1",(r,n)->r.getString(1),workspace,feature).stream().findFirst().map("stopped"::equals).orElse(false);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(JacksonException failure){throw conflict("INVALID_INPUT");}}
    private JsonNode read(String value){try{return json.readTree(value);}catch(JacksonException failure){throw new IllegalStateException(failure);}}
    private static void one(int count){if(count!=1)throw conflict();}private static ApiFailure conflict(){return conflict("STATE_CONFLICT");}
    private static ApiFailure conflict(String code){return ApiFailure.of(HttpStatus.CONFLICT,code);}private static ApiFailure notFound(){return ApiFailure.of(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
    private record Operator(UUID accountId,String role,Instant authenticatedAt){}private record Request(UUID workspaceId,UUID projectId,String ticket,String purpose,String operations,UUID requester){}
}
