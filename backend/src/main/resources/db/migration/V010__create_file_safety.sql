CREATE TABLE file_store.upload_session (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, object_key varchar(512) NOT NULL,
    max_size bigint NOT NULL, part_size bigint NOT NULL, max_parts integer NOT NULL,
    checksum_algorithm varchar(20) NOT NULL, status varchar(40) NOT NULL, expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_upload_session PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_upload_session__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_upload_session__size CHECK (max_size BETWEEN 1 AND 4000000000),
    CONSTRAINT ck_upload_session__part_size CHECK (part_size=67108864),
    CONSTRAINT ck_upload_session__parts CHECK (max_parts BETWEEN 1 AND 60),
    CONSTRAINT ck_upload_session__checksum CHECK (checksum_algorithm='SHA256'),
    CONSTRAINT ck_upload_session__status CHECK (status IN ('initiated','uploading','completed','aborted','expired'))
);

CREATE TABLE file_store.upload_part (
    workspace_id uuid NOT NULL, upload_id uuid NOT NULL, part_no integer NOT NULL,
    expected_size bigint NOT NULL, actual_size bigint NOT NULL, etag varchar(255) NOT NULL,
    checksum_sha256 bytea NOT NULL, completed_at timestamptz NOT NULL,
    CONSTRAINT pk_upload_part PRIMARY KEY (workspace_id,upload_id,part_no),
    CONSTRAINT fk_upload_part__upload__owner FOREIGN KEY (workspace_id,upload_id) REFERENCES file_store.upload_session(workspace_id,id),
    CONSTRAINT ck_upload_part__part_no CHECK (part_no BETWEEN 1 AND 60),
    CONSTRAINT ck_upload_part__size CHECK (expected_size > 0 AND actual_size=expected_size),
    CONSTRAINT ck_upload_part__hash_length CHECK (octet_length(checksum_sha256)=32)
);

CREATE TABLE file_store.file_record (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL,
    logical_name_ciphertext jsonb NOT NULL, owner_id uuid NOT NULL, deletion_status varchar(40) NOT NULL,
    CONSTRAINT pk_file_record PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_file_record__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_file_record__name_object CHECK (jsonb_typeof(logical_name_ciphertext)='object'),
    CONSTRAINT ck_file_record__deletion CHECK (deletion_status IN ('active','requested','deleted'))
);

CREATE TABLE file_store.file_version (
    workspace_id uuid NOT NULL, file_id uuid NOT NULL, id uuid NOT NULL, version_no integer NOT NULL,
    bucket varchar(120) NOT NULL, object_key varchar(512) NOT NULL, object_version_id varchar(255) NOT NULL,
    size_bytes bigint NOT NULL, sha256 bytea NOT NULL, status varchar(40) NOT NULL,
    scan_mode varchar(20) NOT NULL, scan_status varchar(40) NOT NULL, media_type varchar(160) NOT NULL,
    CONSTRAINT pk_file_version PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_file_version__file__owner FOREIGN KEY (workspace_id,file_id) REFERENCES file_store.file_record(workspace_id,id),
    CONSTRAINT uq_file_version__version UNIQUE (workspace_id,file_id,version_no),
    CONSTRAINT uq_file_version__object UNIQUE (bucket,object_key,object_version_id),
    CONSTRAINT ck_file_version__size CHECK (size_bytes BETWEEN 1 AND 4000000000),
    CONSTRAINT ck_file_version__hash_length CHECK (octet_length(sha256)=32),
    CONSTRAINT ck_file_version__status CHECK (status IN ('quarantined','scan_pending','promoting','ready','rejected','deleted')),
    CONSTRAINT ck_file_version__scan_mode CHECK (scan_mode IN ('required','bypass')),
    CONSTRAINT ck_file_version__scan_status CHECK (scan_status IN ('pending','clean','positive','failed','bypassed'))
);

CREATE TABLE file_store.scan_result (
    workspace_id uuid NOT NULL, file_version_id uuid NOT NULL, mode varchar(20) NOT NULL,
    engine varchar(120), definition_version varchar(120), config_hash bytea NOT NULL,
    bytes_read bigint NOT NULL, bytes_scanned bigint NOT NULL, result varchar(40) NOT NULL, completed_at timestamptz NOT NULL,
    CONSTRAINT pk_scan_result PRIMARY KEY (workspace_id,file_version_id),
    CONSTRAINT fk_scan_result__file_version__owner FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT ck_scan_result__mode CHECK (mode IN ('required','bypass')),
    CONSTRAINT ck_scan_result__config_hash CHECK (octet_length(config_hash)=32),
    CONSTRAINT ck_scan_result__bytes CHECK (bytes_read >= 0 AND bytes_scanned >= 0),
    CONSTRAINT ck_scan_result__result CHECK (result IN ('NEGATIVE','POSITIVE','FAILED','BYPASSED')),
    CONSTRAINT ck_scan_result__mode_evidence CHECK (
        (mode='bypass' AND engine IS NULL AND definition_version IS NULL AND result='BYPASSED') OR
        (mode='required' AND engine IS NOT NULL AND definition_version IS NOT NULL AND result IN ('NEGATIVE','POSITIVE','FAILED'))
    )
);

CREATE TABLE file_store.s3_promotion_receipt (
    workspace_id uuid NOT NULL, file_version_id uuid NOT NULL, source_bucket varchar(120) NOT NULL,
    source_version_id varchar(255) NOT NULL, destination_bucket varchar(120) NOT NULL,
    destination_version_id varchar(255) NOT NULL, size_bytes bigint NOT NULL, sha256 bytea NOT NULL,
    verified_at timestamptz NOT NULL,
    CONSTRAINT pk_s3_promotion_receipt PRIMARY KEY (workspace_id,file_version_id),
    CONSTRAINT fk_s3_promotion_receipt__file_version__owner FOREIGN KEY (workspace_id,file_version_id)
        REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT ck_s3_promotion_receipt__size CHECK (size_bytes > 0),
    CONSTRAINT ck_s3_promotion_receipt__hash_length CHECK (octet_length(sha256)=32)
);

CREATE TABLE file_store.preview_asset (
    workspace_id uuid NOT NULL, file_version_id uuid NOT NULL, id uuid NOT NULL, kind varchar(40) NOT NULL,
    bucket varchar(120) NOT NULL, object_key varchar(512) NOT NULL, status varchar(40) NOT NULL, error_code varchar(80),
    CONSTRAINT pk_preview_asset PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_preview_asset__file_version__owner FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT uq_preview_asset__kind UNIQUE (workspace_id,file_version_id,kind),
    CONSTRAINT ck_preview_asset__status CHECK (status IN ('pending','ready','unavailable','deleted')),
    CONSTRAINT ck_preview_asset__error CHECK ((status='unavailable')=(error_code IS NOT NULL))
);

CREATE TABLE file_store.download_grant (
    workspace_id uuid NOT NULL, id uuid NOT NULL, file_version_id uuid NOT NULL, token_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL, auth_generation integer NOT NULL, created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    expires_at timestamptz NOT NULL, revoked_at timestamptz, remaining_uses integer NOT NULL,
    CONSTRAINT pk_download_grant PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_download_grant__file_version__target FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT uq_download_grant__token UNIQUE (digest_key_id,token_digest),
    CONSTRAINT ck_download_grant__digest_length CHECK (octet_length(token_digest)=32),
    CONSTRAINT ck_download_grant__expiry CHECK (expires_at > created_at AND expires_at <= created_at + interval '5 minutes'),
    CONSTRAINT ck_download_grant__uses CHECK (remaining_uses >= 0)
);

CREATE TABLE file_store.external_link (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, url_ciphertext jsonb NOT NULL,
    url_digest bytea NOT NULL, host_ascii varchar(255) NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_external_link PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_external_link__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_external_link__ciphertext_object CHECK (jsonb_typeof(url_ciphertext)='object'),
    CONSTRAINT ck_external_link__digest_length CHECK (octet_length(url_digest)=32),
    CONSTRAINT ck_external_link__status CHECK (status IN ('active','disabled','deleted'))
);

CREATE TABLE project.checkpoint_file_item (
    workspace_id uuid NOT NULL, checkpoint_item_id uuid NOT NULL, file_version_id uuid NOT NULL,
    CONSTRAINT pk_checkpoint_file_item PRIMARY KEY (workspace_id,checkpoint_item_id),
    CONSTRAINT fk_checkpoint_file_item__item__owner FOREIGN KEY (workspace_id,checkpoint_item_id) REFERENCES project.checkpoint_item(workspace_id,id),
    CONSTRAINT fk_checkpoint_file_item__file_version__target FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id)
);
CREATE TABLE project.checkpoint_external_link_item (
    workspace_id uuid NOT NULL, checkpoint_item_id uuid NOT NULL, external_link_id uuid NOT NULL,
    CONSTRAINT pk_checkpoint_external_link_item PRIMARY KEY (workspace_id,checkpoint_item_id),
    CONSTRAINT fk_checkpoint_external_link_item__item__owner FOREIGN KEY (workspace_id,checkpoint_item_id) REFERENCES project.checkpoint_item(workspace_id,id),
    CONSTRAINT fk_checkpoint_external_link_item__link__target FOREIGN KEY (workspace_id,external_link_id) REFERENCES file_store.external_link(workspace_id,id)
);
CREATE TABLE project.checkpoint_text_item (
    workspace_id uuid NOT NULL, checkpoint_item_id uuid NOT NULL, text_snapshot text NOT NULL,
    CONSTRAINT pk_checkpoint_text_item PRIMARY KEY (workspace_id,checkpoint_item_id),
    CONSTRAINT fk_checkpoint_text_item__item__owner FOREIGN KEY (workspace_id,checkpoint_item_id) REFERENCES project.checkpoint_item(workspace_id,id),
    CONSTRAINT ck_checkpoint_text_item__length CHECK (char_length(text_snapshot) BETWEEN 1 AND 20000)
);

CREATE FUNCTION file_store.require_ready_evidence() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,file_store AS $$
BEGIN
    IF NEW.status='ready' AND NOT (
        NEW.scan_status IN ('clean','bypassed')
        AND EXISTS (SELECT 1 FROM file_store.scan_result scan WHERE scan.workspace_id=NEW.workspace_id
            AND scan.file_version_id=NEW.id AND scan.mode=NEW.scan_mode
            AND ((scan.mode='required' AND scan.result='NEGATIVE' AND scan.bytes_read=NEW.size_bytes AND scan.bytes_scanned=NEW.size_bytes)
              OR (scan.mode='bypass' AND scan.result='BYPASSED')))
        AND EXISTS (SELECT 1 FROM file_store.s3_promotion_receipt receipt WHERE receipt.workspace_id=NEW.workspace_id
            AND receipt.file_version_id=NEW.id AND receipt.destination_bucket=NEW.bucket
            AND receipt.destination_version_id=NEW.object_version_id
            AND receipt.size_bytes=NEW.size_bytes AND receipt.sha256=NEW.sha256)
    ) THEN RAISE EXCEPTION 'file version lacks verified readiness evidence' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_file_version_require_ready_evidence AFTER INSERT OR UPDATE ON file_store.file_version
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION file_store.require_ready_evidence();

REVOKE ALL ON ALL TABLES IN SCHEMA file_store FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA file_store TO app_runtime;
