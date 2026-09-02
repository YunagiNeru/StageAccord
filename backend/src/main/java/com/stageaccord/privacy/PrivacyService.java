package com.stageaccord.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.auditadmin.api.DeletionLedgerGateway;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PrivacyService {
    private static final Set<WorkspaceAccess> OWNERS=Set.of(WorkspaceAccess.OWNER);
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WorkspaceAccessGateway workspaces;
    private final IdentityAccessGateway identities;private final DeletionLedgerGateway ledger;private final AuditRecorder audit;private final Clock clock=Clock.systemUTC();
    public PrivacyService(JdbcTemplate jdbc,ObjectMapper json,WorkspaceAccessGateway workspaces,IdentityAccessGateway identities,
            DeletionLedgerGateway ledger,AuditRecorder audit){this.jdbc=jdbc;this.json=json;this.workspaces=workspaces;this.identities=identities;this.ledger=ledger;this.audit=audit;}
    @Transactional public UUID requestWorkspaceExport(String session,UUID workspaceId,String format){UUID actor=workspaces.requireMember(session,workspaceId).accountId();
        if(!List.of("json","html").contains(format))throw invalid("INVALID_EXPORT_FORMAT");JsonNode payload=jdbc.query("SELECT jsonb_build_object('workspaceId',w.id,"
                +"'workspaceStatus',w.status,'projects',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',p.id,'status',p.status)),'[]'::jsonb) FROM project.project p "
                +"WHERE p.workspace_id=w.id),'reports',(SELECT count(*) FROM audit.report r WHERE r.workspace_id=w.id))::text FROM workspace.workspace w WHERE w.id=?",
                (r,n)->read(r.getString(1)),workspaceId).stream().findFirst().orElseThrow(PrivacyService::notFound);UUID id=UUID.randomUUID();Instant now=clock.instant();
        one(jdbc.update("INSERT INTO privacy.data_export(workspace_id,id,subject_id,format,status,requested_at,completed_at,expires_at,payload_json) "
                +"VALUES (?,?,?,?,'ready',?,?,?,?::jsonb)",workspaceId,id,actor,format,now,now,now.plus(Duration.ofHours(24)),canonical(payload)));
        audit.recordAllowed("RequestWorkspaceExport",actor,workspaceId);return id;}
    @Transactional public UUID requestWorkspaceDeletion(String session,UUID workspaceId){UUID actor=workspaces.requireMember(session,workspaceId,OWNERS).accountId();
        Instant now=clock.instant();UUID id=UUID.randomUUID();byte[] subject=sha256(actor.toString());String payload=canonical(json.createObjectNode()
                .put("requestId",id.toString()).put("workspaceId",workspaceId.toString()).put("subjectId",actor.toString()));DeletionLedgerGateway.Receipt receipt;
        try{receipt=ledger.appendDelete(subject,payload.getBytes(StandardCharsets.UTF_8),now);}catch(RuntimeException failure){throw ApiFailure.of(HttpStatus.SERVICE_UNAVAILABLE,
                "DELETION_LEDGER_UNAVAILABLE");}one(jdbc.update("INSERT INTO privacy.deletion_request(workspace_id,id,subject_id,status,requested_at,ledger_acked_at,"
                +"cache_due_at,primary_due_at,backup_due_at) VALUES (?,?,?,'ledger_acked',?,?,?,?,?)",workspaceId,id,actor,now,now,now.plus(Duration.ofHours(24)),
                now.plus(Duration.ofDays(30)),now.plus(Duration.ofDays(35))));one(jdbc.update("INSERT INTO privacy.restoration_ledger_mirror(entry_id,action,subject_digest,"
                +"payload_json,previous_hash,entry_hash,key_id,signature,occurred_at) VALUES (?,'delete',?,?::jsonb,?,?,?,?,?)",receipt.entryId(),subject,payload,
                receipt.previousHash(),receipt.entryHash(),receipt.keyId(),receipt.signature(),now));for(String type:List.of("sessions","publications","files","account"))
            jdbc.update("INSERT INTO privacy.deletion_target(workspace_id,request_id,target_type,target_digest,status,detail_json) VALUES (?,?,?,?,"+
                    "'pending','{}'::jsonb)",workspaceId,id,type,sha256(type+":"+workspaceId));audit.recordAllowed("RequestWorkspaceDeletion",actor,workspaceId);return id;}
    @Transactional(readOnly=true) public JsonNode getDeletionRequest(String session,UUID requestId){UUID actor=identities.resolve(session).accountId();return jdbc.query(
            "SELECT jsonb_build_object('id',id,'workspaceId',workspace_id,'status',status,'requestedAt',requested_at,'ledgerAcknowledgedAt',ledger_acked_at,"
            +"'cacheDueAt',cache_due_at,'primaryDueAt',primary_due_at,'backupDueAt',backup_due_at,'completedAt',completed_at,'targets',(SELECT jsonb_agg("+
            "jsonb_build_object('type',t.target_type,'status',t.status)) FROM privacy.deletion_target t WHERE t.workspace_id=d.workspace_id AND t.request_id=d.id))::text "
            +"FROM privacy.deletion_request d WHERE d.id=? AND d.subject_id=?",(r,n)->read(r.getString(1)),requestId,actor).stream().findFirst().orElseThrow(PrivacyService::notFound);}
    private String canonical(JsonNode value){try{return canonical(json.writeValueAsString(value));}catch(JacksonException failure){throw invalid("INVALID_JSON");}}
    private static String canonical(String value){try{return new String(new JsonCanonicalizer(value).getEncodedUTF8(),StandardCharsets.UTF_8);}catch(java.io.IOException failure){throw invalid("INVALID_JSON");}}
    private JsonNode read(String value){try{return json.readTree(value);}catch(JacksonException failure){throw new IllegalStateException(failure);}}
    private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void one(int count){if(count!=1)throw invalid("STATE_CONFLICT");}private static ApiFailure invalid(String code){return ApiFailure.of(HttpStatus.CONFLICT,code);}
    private static ApiFailure notFound(){return ApiFailure.of(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
}
