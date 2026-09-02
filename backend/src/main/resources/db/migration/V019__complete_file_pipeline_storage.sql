ALTER TABLE file_store.upload_session
    ADD COLUMN provider_upload_id varchar(1024) NOT NULL,
    ADD COLUMN actor_kind varchar(20) NOT NULL,
    ADD COLUMN actor_id uuid NOT NULL,
    ADD COLUMN logical_name_ciphertext jsonb NOT NULL,
    ADD COLUMN media_type varchar(160) NOT NULL,
    ADD CONSTRAINT uq_upload_session__id UNIQUE (id),
    ADD CONSTRAINT ck_upload_session__actor_kind CHECK (actor_kind IN ('creator','client')),
    ADD CONSTRAINT ck_upload_session__logical_name CHECK (jsonb_typeof(logical_name_ciphertext)='object');

ALTER TABLE file_store.file_record ADD CONSTRAINT uq_file_record__id UNIQUE (id);
ALTER TABLE file_store.file_version
    ADD COLUMN scan_started_at timestamptz,
    ADD COLUMN scan_attempts integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT uq_file_version__id UNIQUE (id),
    ADD CONSTRAINT ck_file_version__scan_attempts CHECK (scan_attempts BETWEEN 0 AND 8);
ALTER TABLE file_store.download_grant ADD CONSTRAINT uq_download_grant__id UNIQUE (id);
ALTER TABLE file_store.external_link ADD CONSTRAINT uq_external_link__id UNIQUE (id);

CREATE INDEX ix_file_version__scan_queue ON file_store.file_version(status,scan_started_at)
WHERE status IN ('scan_pending','promoting');

GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA file_store TO app_runtime;
