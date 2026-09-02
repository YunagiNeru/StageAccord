package com.stageaccord.filehandling;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.filehandling.application.ObjectStorageGateway;
import com.stageaccord.filehandling.application.MalwareScanner;

@Component
@Profile("worker")
public class FileInspectionWorker {
    private final JdbcTemplate jdbc;private final ObjectStorageGateway storage;private final MalwareScanner scanner;
    private final Clock clock=Clock.systemUTC();
    public FileInspectionWorker(JdbcTemplate jdbc,ObjectStorageGateway storage,MalwareScanner scanner){
        this.jdbc=jdbc;this.storage=storage;this.scanner=scanner;}

    @Scheduled(fixedDelayString="${stage-accord.worker.file-scan-delay:PT5S}")
    @Transactional
    public void inspectNext(){
        Instant stale=clock.instant().minus(Duration.ofHours(1));
        Pending file=jdbc.query("SELECT workspace_id,id,bucket,object_key,object_version_id,size_bytes,sha256,scan_mode,"
                +"scan_attempts FROM file_store.file_version WHERE (status='scan_pending' OR (status='promoting' AND "
                +"scan_started_at<?)) AND scan_attempts<8 ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1",
                (r,n)->new Pending(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),
                r.getString(5),r.getLong(6),r.getBytes(7),r.getString(8),r.getInt(9)),stale).stream().findFirst().orElse(null);
        if(file==null)return;
        jdbc.update("UPDATE file_store.file_version SET status='promoting',scan_started_at=?,scan_attempts=scan_attempts+1 "
                +"WHERE workspace_id=? AND id=?",clock.instant(),file.workspaceId(),file.id());
        try(InputStream source=storage.openQuarantined(file.objectKey(),file.objectVersionId())){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            var outcome=scanner.scan(new DigestInputStream(source,digest));byte[] computed=digest.digest();
            boolean complete=outcome.bytesRead()==file.sizeBytes()&&Arrays.equals(computed,file.sha256());
            if(outcome.result()==MalwareScanner.Result.FAILED||!complete){retryOrReject(file,outcome,"FAILED");return;}
            if(outcome.result()==MalwareScanner.Result.POSITIVE){recordFinal(file,outcome,"POSITIVE","positive");return;}
            var promoted=storage.promote(file.objectKey(),file.objectVersionId());
            if(promoted.versionId()==null||promoted.versionId().isBlank()||promoted.sizeBytes()!=file.sizeBytes()){
                retryOrReject(file,outcome,"FAILED");return;}
            String result=outcome.result()==MalwareScanner.Result.BYPASSED?"BYPASSED":"NEGATIVE";
            String scanStatus=result.equals("BYPASSED")?"bypassed":"clean";
            insertScan(file,outcome,result);
            jdbc.update("INSERT INTO file_store.s3_promotion_receipt(workspace_id,file_version_id,source_bucket,"
                    +"source_version_id,destination_bucket,destination_version_id,size_bytes,sha256,verified_at) "
                    +"VALUES (?,?,?,?,?,?,?,?,?)",file.workspaceId(),file.id(),file.bucket(),file.objectVersionId(),
                    promoted.bucket(),promoted.versionId(),file.sizeBytes(),file.sha256(),clock.instant());
            jdbc.update("UPDATE file_store.file_version SET bucket=?,object_version_id=?,status='ready',scan_status=? "
                    +"WHERE workspace_id=? AND id=?",promoted.bucket(),promoted.versionId(),scanStatus,file.workspaceId(),file.id());
        }catch(Exception failure){retryOrReject(file,new MalwareScanner.ScanOutcome(MalwareScanner.Result.FAILED,
                file.scanMode().equals("required")?"ClamAV":null,file.scanMode().equals("required")?"unavailable":null,0),"FAILED");}
    }
    private void retryOrReject(Pending file,MalwareScanner.ScanOutcome outcome,String result){if(file.attempts()+1<8){
        jdbc.update("UPDATE file_store.file_version SET status='scan_pending',scan_status='pending' WHERE workspace_id=? AND id=?",
                file.workspaceId(),file.id());return;}recordFinal(file,outcome,result,"failed");}
    private void recordFinal(Pending file,MalwareScanner.ScanOutcome outcome,String result,String scanStatus){
        if(!file.scanMode().equals("bypass")||result.equals("BYPASSED"))insertScan(file,outcome,result);
        jdbc.update("UPDATE file_store.file_version SET status='rejected',scan_status=? "
                +"WHERE workspace_id=? AND id=?",scanStatus,file.workspaceId(),file.id());}
    private void insertScan(Pending file,MalwareScanner.ScanOutcome outcome,String result){byte[] config=sha256(file.scanMode()+":"+
            String.valueOf(outcome.engine())+":"+String.valueOf(outcome.definitionVersion()));jdbc.update("INSERT INTO file_store.scan_result"
            +"(workspace_id,file_version_id,mode,engine,definition_version,config_hash,bytes_read,bytes_scanned,result,completed_at) "
            +"VALUES (?,?,?,?,?,?,?,?,?,?)",file.workspaceId(),file.id(),file.scanMode(),outcome.engine(),outcome.definitionVersion(),
            config,outcome.bytesRead(),outcome.bytesRead(),result,clock.instant());}
    private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception impossible){throw new IllegalStateException(HexFormat.of().formatHex(value.getBytes()),impossible);}}
    private record Pending(UUID workspaceId,UUID id,String bucket,String objectKey,String objectVersionId,long sizeBytes,
            byte[] sha256,String scanMode,int attempts){public Pending{sha256=sha256.clone();}@Override public byte[] sha256(){return sha256.clone();}}
}
