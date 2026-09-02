package com.stageaccord.collaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.collaboration.domain.ApprovalPolicy;
import com.stageaccord.collaboration.domain.CollaborationRuleViolation;
import com.stageaccord.collaboration.domain.DeliveryPolicy;
import com.stageaccord.collaboration.domain.RevisionPolicy;
import com.stageaccord.identityaccess.api.AuthenticatedClient;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;

import org.erdtman.jcs.JsonCanonicalizer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@Service
public class CollaborationService {
    private static final Set<WorkspaceAccess> CONTRIBUTORS=Set.of(WorkspaceAccess.OWNER,WorkspaceAccess.ADMIN,
            WorkspaceAccess.PROJECT_MANAGER,WorkspaceAccess.MEMBER);
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WorkspaceAccessGateway workspaces;
    private final IdentityAccessGateway identities;private final AuditRecorder audit;private final Clock clock;
    private final RevisionPolicy revisions=new RevisionPolicy();private final ApprovalPolicy approvals=new ApprovalPolicy();
    private final DeliveryPolicy deliveries=new DeliveryPolicy();
    public CollaborationService(JdbcTemplate jdbc,ObjectMapper json,WorkspaceAccessGateway workspaces,
            IdentityAccessGateway identities,AuditRecorder audit){this(jdbc,json,workspaces,identities,audit,Clock.systemUTC());}
    CollaborationService(JdbcTemplate jdbc,ObjectMapper json,WorkspaceAccessGateway workspaces,
            IdentityAccessGateway identities,AuditRecorder audit,Clock clock){this.jdbc=jdbc;this.json=json;
        this.workspaces=workspaces;this.identities=identities;this.audit=audit;this.clock=clock;}

    @Transactional public UUID publishProgress(String session,UUID workspaceId,UUID checkpointId,String visibility,String body){
        UUID actor=workspaces.requireMember(session,workspaceId,CONTRIBUTORS).accountId();Checkpoint checkpoint=checkpoint(workspaceId,checkpointId);
        if(!List.of("private","client").contains(visibility))throw invalid("INVALID_VISIBILITY");UUID id=UUID.randomUUID(),thread=id;
        one(jdbc.update("INSERT INTO collab.progress_update(workspace_id,project_id,checkpoint_id,id,thread_id,version_no,"
                +"author_id,visibility,body_ciphertext,status,created_at,published_at) VALUES (?,?,?,?,?,1,?,?,?::jsonb,'published',?,?)",
                workspaceId,checkpoint.projectId(),checkpointId,id,thread,actor,visibility,protect(body),clock.instant(),clock.instant()));
        if(visibility.equals("client"))notifyClient(workspaceId,checkpoint.projectId(),id,"activity","progress.published");
        audit.recordAllowed("PublishProgress",actor,workspaceId);return id;}

    @Transactional public UUID postComment(String session,UUID checkpointId,String body,String targetType,UUID targetId,
            int targetVersion,Long timeOffsetMs,JsonNode position){AuthenticatedClient client=requireClientCheckpoint(session,checkpointId);
        UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO collab.comment(workspace_id,checkpoint_id,id,author_id,"
                +"body_ciphertext,target_type,target_id,target_version,time_offset_ms,position_json,created_at) "
                +"VALUES (?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?)",client.workspaceId(),checkpointId,id,clientParty(client),
                protect(body),targetType,targetId,targetVersion,timeOffsetMs,position==null?null:write(position),clock.instant()));
        notifyCreators(client.workspaceId(),client.projectId(),id,"activity","comment.posted");
        audit.recordAllowed("PostComment",client.sessionId(),client.workspaceId());return id;}

    @Transactional public UUID saveRevisionDraft(String session,UUID checkpointId,String body,String targetType,
            UUID targetId,int targetVersion,long expectedVersion){AuthenticatedClient client=requireClientCheckpoint(session,checkpointId);
        UUID author=clientParty(client);UUID id=jdbc.query("SELECT id FROM collab.revision_draft WHERE workspace_id=? "
                +"AND checkpoint_id=? AND author_id=? AND status='draft' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),
                client.workspaceId(),checkpointId,author).stream().findFirst().orElse(null);
        if(id==null){id=UUID.randomUUID();one(jdbc.update("INSERT INTO collab.revision_draft(workspace_id,checkpoint_id,id,"
                +"author_id,body_ciphertext,target_type,target_id,target_version,status) VALUES (?,?,?,?,?::jsonb,?,?,?,'draft')",
                client.workspaceId(),checkpointId,id,author,protect(body),targetType,targetId,targetVersion));}
        else one(jdbc.update("UPDATE collab.revision_draft SET body_ciphertext=?::jsonb,target_type=?,target_id=?,target_version=?,"
                +"version=version+1 WHERE workspace_id=? AND id=? AND version=?",protect(body),targetType,targetId,targetVersion,
                client.workspaceId(),id,expectedVersion));audit.recordAllowed("SaveRevisionDraft",client.sessionId(),client.workspaceId());return id;}

    @Transactional public UUID submitRevisionRound(String session,UUID checkpointId,String classification,UUID changeOrderId){
        AuthenticatedClient client=requireClientCheckpoint(session,checkpointId);UUID author=clientParty(client);
        int items=jdbc.queryForObject("SELECT count(*) FROM collab.revision_draft WHERE workspace_id=? AND checkpoint_id=? "
                +"AND author_id=? AND status='draft'",Integer.class,client.workspaceId(),checkpointId,author);
        int used=jdbc.queryForObject("SELECT count(*) FROM collab.revision_round WHERE workspace_id=? AND checkpoint_id=? "
                +"AND consumes_quota",Integer.class,client.workspaceId(),checkpointId);boolean changeAccepted=changeOrderId!=null&&
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agreement.change_order WHERE workspace_id=? AND project_id=? "
                +"AND id=? AND status='accepted')",Boolean.class,client.workspaceId(),client.projectId(),changeOrderId);
        RevisionPolicy.Classification kind=parseClassification(classification);var decision=translate(()->revisions.requireSubmittable(
                kind,items,Math.max(0,2-used),true,changeAccepted));UUID agreement=jdbc.queryForObject("SELECT agreement_version_id "
                +"FROM project.project WHERE workspace_id=? AND id=?",UUID.class,client.workspaceId(),client.projectId());
        int round=jdbc.queryForObject("SELECT coalesce(max(round_no),0)+1 FROM collab.revision_round WHERE workspace_id=? AND checkpoint_id=?",
                Integer.class,client.workspaceId(),checkpointId);UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO collab.revision_round"
                +"(workspace_id,checkpoint_id,id,agreement_version_id,round_no,classification,item_count,consumes_quota,change_order_id,"
                +"status,submitted_at) VALUES (?,?,?,?,?,?,?,?,?,'requested',?)",client.workspaceId(),checkpointId,id,agreement,
                round,classification,items,decision.consumesOneRound(),changeOrderId,clock.instant()));jdbc.update("UPDATE collab.revision_draft "
                +"SET status='submitted',version=version+1 WHERE workspace_id=? AND checkpoint_id=? AND author_id=? AND status='draft'",
                client.workspaceId(),checkpointId,author);notifyCreators(client.workspaceId(),client.projectId(),id,"transaction","revision.submitted");
        audit.recordAllowed("SubmitRevisionRound",client.sessionId(),client.workspaceId());return id;}

    @Transactional public UUID recordApprovalAction(String session,UUID checkpointId,ApprovalRequest request){
        AuthenticatedClient client=requireFreshClientCheckpoint(session,checkpointId);UUID actor=clientParty(client);
        if(!List.of("approved","rejected").contains(request.decision()))throw invalid("INVALID_DECISION");
        byte[] hash=decodeHash(request.targetSha256());UUID approvalId=jdbc.query("SELECT id FROM collab.approval_process WHERE "
                +"workspace_id=? AND checkpoint_id=? AND status='pending' FOR UPDATE",(r,n)->r.getObject(1,UUID.class),client.workspaceId(),
                checkpointId).stream().findFirst().orElse(null);if(approvalId==null){approvalId=UUID.randomUUID();one(jdbc.update("INSERT INTO "
                +"collab.approval_process(workspace_id,checkpoint_id,id,target_type,target_id,target_version,target_sha256,rule_json,"
                +"required_approvals,status,created_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,'pending',?)",client.workspaceId(),checkpointId,
                approvalId,request.targetType(),request.targetId(),request.targetVersion(),hash,write(request.rule()),request.requiredApprovals(),clock.instant()));}
        byte[] expected=jdbc.queryForObject("SELECT target_sha256 FROM collab.approval_process WHERE workspace_id=? AND id=?",
                byte[].class,client.workspaceId(),approvalId);ApprovalPolicy.Decision decision=request.decision().equals("approved")?
                ApprovalPolicy.Decision.APPROVED:ApprovalPolicy.Decision.REJECTED;translateVoid(()->approvals.requireAction(expected,
                new ApprovalPolicy.Action(actor.toString(),true,request.explicitUserAction(),client.authenticatedAt(),hash,decision),clock.instant(),true));
        one(jdbc.update("INSERT INTO collab.approval_action(workspace_id,approval_id,actor_id,decision,target_sha256,"
                +"explicit_user_action,authenticated_at,acted_at) VALUES (?,?,?,?,?,?,?,?)",client.workspaceId(),approvalId,actor,
                request.decision(),hash,true,client.authenticatedAt(),clock.instant()));if(decision==ApprovalPolicy.Decision.REJECTED)
            jdbc.update("UPDATE collab.approval_process SET status='rejected' WHERE workspace_id=? AND id=?",client.workspaceId(),approvalId);
        else{int required=jdbc.queryForObject("SELECT required_approvals FROM collab.approval_process WHERE workspace_id=? AND id=?",
                Integer.class,client.workspaceId(),approvalId);int count=jdbc.queryForObject("SELECT count(*) FROM collab.approval_action "
                +"WHERE workspace_id=? AND approval_id=? AND decision='approved'",Integer.class,client.workspaceId(),approvalId);
            if(count>=required){jdbc.update("UPDATE collab.approval_process SET status='approved',satisfied_at=? WHERE workspace_id=? AND id=?",
                    clock.instant(),client.workspaceId(),approvalId);completeCheckpoint(client.workspaceId(),client.projectId(),checkpointId);}}
        audit.recordAllowed("RecordApprovalAction",client.sessionId(),client.workspaceId());return approvalId;}

    @Transactional public UUID freezeDeliveryPackage(String session,UUID workspaceId,UUID projectId,JsonNode manifest,
            String terms,String credits,String notes,List<DeliveryItemRequest> requested){UUID actor=workspaces.requireMember(session,workspaceId,
            CONTRIBUTORS).accountId();List<DeliveryRow> rows=requested.stream().map(item->file(workspaceId,projectId,item)).toList();
        translateVoid(()->deliveries.requireFreezable(rows.stream().map(r->new DeliveryPolicy.DeliveryItem(r.ready(),r.size(),r.hash())).toList()));
        UUID agreement=jdbc.queryForObject("SELECT agreement_version_id FROM project.project WHERE workspace_id=? AND id=? AND status='completed'",
                UUID.class,workspaceId,projectId);int number=jdbc.queryForObject("SELECT coalesce(max(package_no),0)+1 FROM collab.delivery_package "
                +"WHERE workspace_id=? AND project_id=?",Integer.class,workspaceId,projectId);UUID id=UUID.randomUUID();Instant now=clock.instant();
        String canonicalManifest=canonical(write(manifest));
        one(jdbc.update("INSERT INTO collab.delivery_package(workspace_id,project_id,id,agreement_version_id,package_no,manifest_json,"
                +"terms_ciphertext,credits_ciphertext,notes_ciphertext,status,prepared_at,frozen_at,delivered_at) "
                +"VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'delivered',?,?,?)",workspaceId,projectId,id,agreement,number,
                canonicalManifest,protect(terms),protect(credits),protect(notes),now,now,now));for(int index=0;index<rows.size();index++){var row=rows.get(index);
            one(jdbc.update("INSERT INTO collab.delivery_item(workspace_id,package_id,file_version_id,ordinal,label,sha256,size_bytes) "
                    +"VALUES (?,?,?,?,?,?,?)",workspaceId,id,row.id(),index+1,requested.get(index).label(),row.hash(),row.size()));}
        notifyClient(workspaceId,projectId,id,"transaction","delivery.ready");audit.recordAllowed("FreezeDeliveryPackage",actor,workspaceId);return id;}

    @Transactional public void receiveDelivery(String session,UUID deliveryId,String decision,String manifestSha256,
            boolean explicit){AuthenticatedClient client=identities.resolveClient(session);DeliveryTarget target=jdbc.query("SELECT d.project_id,"
                +"d.manifest_json::text,d.status FROM collab.delivery_package d WHERE d.workspace_id=? AND d.project_id=? AND d.id=? FOR UPDATE",
                (r,n)->new DeliveryTarget(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),client.workspaceId(),client.projectId(),deliveryId)
                .stream().findFirst().orElseThrow(CollaborationService::notFound);byte[] expected=sha256(canonical(target.manifest()));byte[] displayed=decodeHash(manifestSha256);
        if(!List.of("received","issue_reported").contains(decision))throw invalid("INVALID_DECISION");
        translateVoid(()->deliveries.requireReceivable(target.status().equals("delivered"),expected,new DeliveryPolicy.Receipt(explicit,
                client.authenticatedAt(),displayed),clock.instant(),true));UUID actor=clientParty(client);one(jdbc.update("INSERT INTO collab.delivery_receipt"
                +"(workspace_id,package_id,actor_id,decision,package_manifest_sha256,explicit_user_action,authenticated_at,received_at) "
                +"VALUES (?,?,?,?,?,true,?,?)",client.workspaceId(),deliveryId,actor,decision,expected,client.authenticatedAt(),clock.instant()));
        one(jdbc.update("UPDATE collab.delivery_package SET status=? WHERE workspace_id=? AND id=? AND status='delivered'",
                decision,client.workspaceId(),deliveryId));audit.recordAllowed("ReceiveDelivery",client.sessionId(),client.workspaceId());}

    @Transactional public UUID exportCreatorProjectRecord(String session,UUID workspaceId,UUID projectId){UUID actor=
        workspaces.requireMember(session,workspaceId,CONTRIBUTORS).accountId();return export(workspaceId,projectId,actor,"creator","ExportCreatorProjectRecord");}
    @Transactional public UUID exportClientProjectRecord(String session,UUID projectAccessId){var client=requireProjectAccess(session,projectAccessId);
        return export(client.workspaceId(),client.projectId(),clientParty(client),"client","ExportClientProjectRecord");}
    private UUID export(UUID workspaceId,UUID projectId,UUID actor,String audience,String operation){JsonNode payload=jdbc.query("SELECT "
            +"jsonb_build_object('projectId',p.id,'status',p.status,'waitingOn',p.waiting_on,'agreementVersionId',p.agreement_version_id,"
            +"'checkpointCount',(SELECT count(*) FROM project.checkpoint_instance c WHERE c.workspace_id=p.workspace_id AND c.project_id=p.id),"
            +"'deliveryCount',(SELECT count(*) FROM collab.delivery_package d WHERE d.workspace_id=p.workspace_id AND d.project_id=p.id))::text "
            +"FROM project.project p WHERE p.workspace_id=? AND p.id=?",(r,n)->read(r.getString(1)),workspaceId,projectId).stream().findFirst()
            .orElseThrow(CollaborationService::notFound);UUID id=UUID.randomUUID();one(jdbc.update("INSERT INTO collab.project_export"
            +"(workspace_id,project_id,id,requested_by,audience,payload_json,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",workspaceId,projectId,id,
            actor,audience,write(payload),clock.instant()));audit.recordAllowed(operation,actor,workspaceId);return id;}

    @Transactional(readOnly=true) public List<JsonNode> listNotifications(String creatorSession,String clientSession){UUID principal;
        if(creatorSession!=null)principal=identities.resolve(creatorSession).accountId();else principal=clientParty(identities.resolveClient(clientSession));
        return jdbc.query("SELECT jsonb_build_object('id',id,'category',category,'templateKey',template_key,'data',template_data,"
                +"'createdAt',created_at,'readAt',read_at)::text FROM schedule.notification_request WHERE principal_id=? ORDER BY created_at DESC",
                (r,n)->read(r.getString(1)),principal);}
    @Transactional public void markNotificationRead(String creatorSession,String clientSession,UUID notificationId){UUID principal=creatorSession!=null?
        identities.resolve(creatorSession).accountId():clientParty(identities.resolveClient(clientSession));one(jdbc.update("UPDATE schedule.notification_request "
                +"SET read_at=coalesce(read_at,?) WHERE id=? AND principal_id=?",clock.instant(),notificationId,principal));}
    @Transactional public void updateNotificationPreference(String session,UUID workspaceId,String category,String channel,String mode){UUID actor=
        workspaces.requireMember(session,workspaceId).accountId();savePreference(workspaceId,actor,category,channel,mode);audit.recordAllowed(
                "UpdateNotificationPreference",actor,workspaceId);}
    @Transactional public void updateClientNotificationPreference(String session,String category,String channel,String mode){var client=identities.resolveClient(session);
        UUID actor=clientParty(client);savePreference(client.workspaceId(),actor,category,channel,mode);audit.recordAllowed("UpdateClientNotificationPreference",
                client.sessionId(),client.workspaceId());}
    private void savePreference(UUID workspaceId,UUID principal,String category,String channel,String mode){if(!category.equals("activity")&&mode.equals("disabled"))
        throw invalid("MANDATORY_NOTIFICATION_CANNOT_BE_DISABLED");jdbc.update("INSERT INTO schedule.notification_preference(workspace_id,principal_id,"
                +"category,channel,mode) VALUES (?,?,?,?,?) ON CONFLICT (workspace_id,principal_id,category,channel) DO UPDATE SET mode=EXCLUDED.mode,"
                +"version=schedule.notification_preference.version+1",workspaceId,principal,category,channel,mode);}

    private void notifyClient(UUID workspaceId,UUID projectId,UUID event,String category,String template){UUID party=jdbc.query("SELECT party_id FROM "
            +"project.client_access WHERE workspace_id=? AND project_id=? AND revoked_at IS NULL",(r,n)->r.getObject(1,UUID.class),workspaceId,projectId)
            .stream().findFirst().orElse(null);if(party!=null)insertNotification(workspaceId,projectId,event,party,category,template);}
    private void notifyCreators(UUID workspaceId,UUID projectId,UUID event,String category,String template){List<UUID> accounts=jdbc.query("SELECT account_id "
            +"FROM workspace.membership WHERE workspace_id=? AND status='active'",(r,n)->r.getObject(1,UUID.class),workspaceId);
        accounts.forEach(account->insertNotification(workspaceId,projectId,event,account,category,template));}
    private void insertNotification(UUID workspaceId,UUID projectId,UUID event,UUID principal,String category,String template){jdbc.update("INSERT INTO "
            +"schedule.notification_request(workspace_id,project_id,id,principal_id,event_id,category,template_key,template_data,status,created_at) "
            +"VALUES (?,?,?,?,?,?,?,'{}'::jsonb,'queued',?) ON CONFLICT (workspace_id,event_id,principal_id,template_key) DO NOTHING",workspaceId,projectId,
            UUID.randomUUID(),principal,event,category,template,clock.instant());}
    private void completeCheckpoint(UUID workspaceId,UUID projectId,UUID checkpointId){jdbc.update("UPDATE project.checkpoint_instance SET status='completed',"
            +"version=version+1 WHERE workspace_id=? AND project_id=? AND id=?",workspaceId,projectId,checkpointId);Integer seq=jdbc.queryForObject(
            "SELECT sequence_no FROM project.checkpoint_instance WHERE workspace_id=? AND id=?",Integer.class,workspaceId,checkpointId);UUID next=jdbc.query(
            "SELECT id FROM project.checkpoint_instance WHERE workspace_id=? AND project_id=? AND sequence_no=?",(r,n)->r.getObject(1,UUID.class),workspaceId,
            projectId,seq+1).stream().findFirst().orElse(null);if(next==null)jdbc.update("UPDATE project.project SET status='completed',waiting_on='NONE',"
            +"current_checkpoint_id=NULL,version=version+1 WHERE workspace_id=? AND id=?",workspaceId,projectId);else{jdbc.update("UPDATE project.checkpoint_instance "
            +"SET status='active' WHERE workspace_id=? AND id=?",workspaceId,next);jdbc.update("UPDATE project.project SET waiting_on='CREATOR',"
            +"current_checkpoint_id=?,version=version+1 WHERE workspace_id=? AND id=?",next,workspaceId,projectId);}}
    private Checkpoint checkpoint(UUID workspaceId,UUID id){return jdbc.query("SELECT project_id,status FROM project.checkpoint_instance WHERE workspace_id=? "
            +"AND id=?",(r,n)->new Checkpoint(r.getObject(1,UUID.class),r.getString(2)),workspaceId,id).stream().findFirst().orElseThrow(CollaborationService::notFound);}
    private AuthenticatedClient requireClientCheckpoint(String token,UUID checkpointId){var c=identities.resolveClient(token);boolean allowed=jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM project.checkpoint_instance WHERE workspace_id=? AND project_id=? AND id=?)",Boolean.class,c.workspaceId(),c.projectId(),checkpointId);
        if(!allowed)throw notFound();return c;}private AuthenticatedClient requireFreshClientCheckpoint(String token,UUID checkpointId){var c=requireClientCheckpoint(token,checkpointId);
        if(Duration.between(c.authenticatedAt(),clock.instant()).isNegative()||Duration.between(c.authenticatedAt(),clock.instant()).compareTo(Duration.ofMinutes(30))>0)
            throw ApiFailure.of(HttpStatus.UNAUTHORIZED,"AUTH_FRESHNESS_REQUIRED");return c;}
    private AuthenticatedClient requireProjectAccess(String token,UUID accessId){var c=identities.resolveClient(token);boolean allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 "
            +"FROM project.client_access WHERE id=? AND workspace_id=? AND project_id=? AND revoked_at IS NULL AND expires_at>?)",Boolean.class,accessId,
            c.workspaceId(),c.projectId(),clock.instant());if(!allowed)throw notFound();return c;}private UUID clientParty(AuthenticatedClient c){return jdbc.query("SELECT party_id FROM "
            +"project.client_access WHERE workspace_id=? AND project_id=? AND revoked_at IS NULL",(r,n)->r.getObject(1,UUID.class),c.workspaceId(),c.projectId()).stream()
            .findFirst().orElseThrow(CollaborationService::notFound);}
    private DeliveryRow file(UUID workspaceId,UUID projectId,DeliveryItemRequest item){return jdbc.query("SELECT v.id,v.status='ready',v.size_bytes,v.sha256 FROM "
            +"file_store.file_version v JOIN file_store.file_record f ON f.workspace_id=v.workspace_id AND f.id=v.file_id WHERE v.workspace_id=? AND f.project_id=? "
            +"AND v.id=? AND f.deletion_status='active'",(r,n)->new DeliveryRow(r.getObject(1,UUID.class),r.getBoolean(2),r.getLong(3),r.getBytes(4)),workspaceId,
            projectId,item.fileVersionId()).stream().findFirst().orElseThrow(CollaborationService::notFound);}
    private RevisionPolicy.Classification parseClassification(String value){try{return RevisionPolicy.Classification.valueOf(value.toUpperCase());}
        catch(IllegalArgumentException failure){throw invalid("INVALID_CLASSIFICATION");}}
    private String protect(String value){return write(identities.protectContact(value));}private String write(Object value){try{return json.writeValueAsString(value);}
        catch(JacksonException failure){throw invalid("INVALID_INPUT");}}private JsonNode read(String value){try{return json.readTree(value);}catch(JacksonException failure){throw new IllegalStateException(failure);}}
    private static String canonical(String value){try{return new String(new JsonCanonicalizer(value).getEncodedUTF8(),StandardCharsets.UTF_8);}
        catch(java.io.IOException failure){throw invalid("INVALID_JSON");}}
    private byte[] decodeHash(String value){try{byte[] hash=Base64.getDecoder().decode(value);if(hash.length!=32)throw new IllegalArgumentException();return hash;}
        catch(IllegalArgumentException failure){throw invalid("INVALID_HASH");}}private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}}private static <T>T translate(Action<T> action){try{return action.run();}catch(CollaborationRuleViolation failure){throw invalid(failure.reason().name());}}
    private static void translateVoid(VoidAction action){try{action.run();}catch(CollaborationRuleViolation failure){throw invalid(failure.reason().name());}}
    private static void one(int count){if(count!=1)throw conflict();}private static ApiFailure invalid(String code){return ApiFailure.of(HttpStatus.CONFLICT,code);}
    private static ApiFailure conflict(){return ApiFailure.of(HttpStatus.CONFLICT,"STATE_CONFLICT");}private static ApiFailure notFound(){return ApiFailure.of(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
    @FunctionalInterface private interface Action<T>{T run();}@FunctionalInterface private interface VoidAction{void run();}
    public record ApprovalRequest(String targetType,UUID targetId,int targetVersion,String targetSha256,JsonNode rule,int requiredApprovals,String decision,boolean explicitUserAction){}
    public record DeliveryItemRequest(UUID fileVersionId,String label){}private record Checkpoint(UUID projectId,String status){}
    private record DeliveryRow(UUID id,boolean ready,long size,byte[] hash){public DeliveryRow{hash=hash.clone();}@Override public byte[] hash(){return hash.clone();}}
    private record DeliveryTarget(UUID projectId,String manifest,String status){}
}
