package com.stageaccord.filehandling;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.filehandling.api.FileDeletionGateway;
import com.stageaccord.filehandling.application.ObjectStorageGateway;

@Component
public class FileDeletionAdapter implements FileDeletionGateway {
    private final JdbcTemplate jdbc;private final ObjectStorageGateway storage;
    public FileDeletionAdapter(JdbcTemplate jdbc,ObjectStorageGateway storage){this.jdbc=jdbc;this.storage=storage;}
    @Override @Transactional public int deleteProjectFiles(UUID workspaceId){var files=jdbc.query("SELECT v.id,v.bucket,v.object_key,v.object_version_id "
            +"FROM file_store.file_version v JOIN file_store.file_record f ON f.workspace_id=v.workspace_id AND f.id=v.file_id WHERE v.workspace_id=? "
            +"AND v.status<>'deleted'",(r,n)->new FileObject(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4)),workspaceId);
        for(var file:files){if(file.bucket().contains("clean"))storage.deleteClean(file.key(),file.version());else storage.deleteQuarantined(file.key(),file.version());
            jdbc.update("UPDATE file_store.file_version SET status='deleted' WHERE workspace_id=? AND id=?",workspaceId,file.id());}
        jdbc.update("UPDATE file_store.file_record SET deletion_status='deleted' WHERE workspace_id=?",workspaceId);jdbc.update("UPDATE file_store.download_grant "
                +"SET revoked_at=coalesce(revoked_at,transaction_timestamp()) WHERE workspace_id=?",workspaceId);return files.size();}
    private record FileObject(UUID id,String bucket,String key,String version){}
}
