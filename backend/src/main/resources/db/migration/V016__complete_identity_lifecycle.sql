CREATE TABLE iam.client_session (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    token_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    email_digest_v2 bytea NOT NULL,
    client_role varchar(40) NOT NULL,
    auth_generation integer NOT NULL,
    authenticated_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_client_session PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_client_session__project__tenant FOREIGN KEY (workspace_id, project_id)
        REFERENCES project.project (workspace_id, id),
    CONSTRAINT uq_client_session__id UNIQUE (id),
    CONSTRAINT uq_client_session__token UNIQUE (digest_key_id, token_digest),
    CONSTRAINT ck_client_session__token_length CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_client_session__email_length CHECK (octet_length(email_digest_v2) = 32),
    CONSTRAINT ck_client_session__role CHECK (client_role IN ('requester', 'approver', 'viewer')),
    CONSTRAINT ck_client_session__generation CHECK (auth_generation >= 0),
    CONSTRAINT ck_client_session__expiry CHECK (
        absolute_expires_at <= authenticated_at + interval '24 hours'
        AND last_seen_at <= absolute_expires_at
    )
);

CREATE INDEX ix_client_session__expiry_reaper ON iam.client_session (absolute_expires_at)
WHERE revoked_at IS NULL;
COMMENT ON INDEX iam.ix_client_session__expiry_reaper IS 'expiry_reaper';

REVOKE ALL ON iam.client_session FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON iam.client_session TO app_runtime;
