CREATE TABLE intake.request_access (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_request_access PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_request_access__request__owner FOREIGN KEY (workspace_id, request_id)
        REFERENCES intake.request (workspace_id, id),
    CONSTRAINT uq_request_access__id UNIQUE (id),
    CONSTRAINT uq_request_access__request UNIQUE (workspace_id, request_id)
);

CREATE INDEX ix_request_access__expiry_reaper ON intake.request_access (expires_at)
WHERE revoked_at IS NULL;
COMMENT ON INDEX intake.ix_request_access__expiry_reaper IS 'expiry_reaper';

REVOKE ALL ON intake.request_access FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON intake.request_access TO app_runtime;
