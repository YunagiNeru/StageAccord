package com.stageaccord.privacy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.filehandling.api.FileDeletionGateway;

@Component
@Profile("worker")
public class PrivacyDeletionWorker {
    private final JdbcTemplate jdbc;private final FileDeletionGateway files;private final Clock clock=Clock.systemUTC();
    public PrivacyDeletionWorker(JdbcTemplate jdbc,FileDeletionGateway files){this.jdbc=jdbc;this.files=files;}
    @Scheduled(fixedDelayString="${stage-accord.worker.privacy-delay:PT30S}")
    @Transactional public void processNext(){Request request=jdbc.query("SELECT workspace_id,id,subject_id,processing_attempts FROM privacy.deletion_request "
                +"WHERE status IN ('ledger_acked','failed') AND processing_attempts<8 ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1",
                (r,n)->new Request(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getInt(4))).stream().findFirst().orElse(null);
        if(request==null)return;jdbc.update("UPDATE privacy.deletion_request SET status='processing',processing_started_at=coalesce(processing_started_at,?),"
                +"processing_attempts=processing_attempts+1 WHERE workspace_id=? AND id=?",clock.instant(),request.workspaceId(),request.id());try{
            jdbc.update("UPDATE iam.session_record SET revoked_at=coalesce(revoked_at,?) WHERE account_id=?",clock.instant(),request.subjectId());
            jdbc.update("UPDATE iam.credential SET status='revoked' WHERE account_id=? AND status<>'revoked'",request.subjectId());
            jdbc.update("UPDATE iam.client_access_grant SET revoked_at=coalesce(revoked_at,?) WHERE workspace_id=?",clock.instant(),request.workspaceId());
            jdbc.update("UPDATE catalog.creator_profile SET intake_status='closed',published_version_id=NULL WHERE workspace_id=?",request.workspaceId());
            jdbc.update("UPDATE catalog.service SET status='archived' WHERE workspace_id=?",request.workspaceId());
            jdbc.update("DELETE FROM catalog.public_service_projection WHERE workspace_id=?",request.workspaceId());
            jdbc.update("DELETE FROM catalog.public_profile_projection WHERE workspace_id=?",request.workspaceId());files.deleteProjectFiles(request.workspaceId());
            jdbc.update("UPDATE iam.account SET status='closed',auth_generation=auth_generation+1,version=version+1 WHERE id=?",request.subjectId());
            jdbc.update("UPDATE privacy.deletion_target SET status='deleted',deleted_at=? WHERE workspace_id=? AND request_id=? AND status='pending'",
                    clock.instant(),request.workspaceId(),request.id());jdbc.update("UPDATE privacy.deletion_request SET status='completed',completed_at=?,version=version+1 "
                    +"WHERE workspace_id=? AND id=?",clock.instant(),request.workspaceId(),request.id());
        }catch(RuntimeException failure){jdbc.update("UPDATE privacy.deletion_request SET status='failed',version=version+1 WHERE workspace_id=? AND id=?",
                request.workspaceId(),request.id());}}
    private record Request(UUID workspaceId,UUID id,UUID subjectId,int attempts){}
}
