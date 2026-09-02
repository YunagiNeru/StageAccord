CREATE TABLE iam.auth_challenge (
    id uuid NOT NULL,
    account_id uuid,
    purpose varchar(40) NOT NULL,
    challenge_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    subject_digest bytea,
    protected_context jsonb NOT NULL DEFAULT '{}'::jsonb,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT pk_auth_challenge PRIMARY KEY (id),
    CONSTRAINT fk_auth_challenge__account__owner FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT uq_auth_challenge__purpose_digest UNIQUE (purpose, digest_key_id, challenge_digest),
    CONSTRAINT ck_auth_challenge__purpose CHECK (purpose IN (
        'email_verification', 'authentication', 'reauthentication',
        'passkey_enrollment', 'totp_enrollment', 'account_recovery'
    )),
    CONSTRAINT ck_auth_challenge__digest_length CHECK (octet_length(challenge_digest) = 32),
    CONSTRAINT ck_auth_challenge__subject_length CHECK (
        subject_digest IS NULL OR octet_length(subject_digest) = 32
    ),
    CONSTRAINT ck_auth_challenge__context_object CHECK (jsonb_typeof(protected_context) = 'object'),
    CONSTRAINT ck_auth_challenge__expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_challenge__consumption CHECK (
        consumed_at IS NULL OR consumed_at BETWEEN created_at AND expires_at
    )
);

CREATE INDEX ix_auth_challenge__expiry_reaper ON iam.auth_challenge (expires_at)
WHERE consumed_at IS NULL;
COMMENT ON INDEX iam.ix_auth_challenge__expiry_reaper IS 'expiry_reaper';

CREATE TABLE iam.client_access_grant (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    token_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    project_id uuid NOT NULL,
    email_digest_v2 bytea NOT NULL,
    client_role varchar(40) NOT NULL,
    auth_generation integer NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT pk_client_access_grant PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_client_access_grant__project__tenant FOREIGN KEY (workspace_id, project_id)
        REFERENCES project.project (workspace_id, id),
    CONSTRAINT uq_client_access_grant__token UNIQUE (digest_key_id, token_digest),
    CONSTRAINT ck_client_access_grant__token_length CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_client_access_grant__email_length CHECK (octet_length(email_digest_v2) = 32),
    CONSTRAINT ck_client_access_grant__role CHECK (client_role IN ('requester', 'approver', 'viewer')),
    CONSTRAINT ck_client_access_grant__generation CHECK (auth_generation >= 0),
    CONSTRAINT ck_client_access_grant__terminal CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

CREATE INDEX ix_client_access_grant__expiry_reaper
ON iam.client_access_grant (workspace_id, expires_at)
WHERE consumed_at IS NULL AND revoked_at IS NULL;
COMMENT ON INDEX iam.ix_client_access_grant__expiry_reaper IS 'expiry_reaper';

REVOKE ALL ON iam.auth_challenge, iam.client_access_grant FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON iam.auth_challenge, iam.client_access_grant TO app_runtime;
