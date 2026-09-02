CREATE TABLE iam.account (
    id uuid NOT NULL,
    email_digest_v2 bytea NOT NULL,
    email_ciphertext jsonb NOT NULL,
    status varchar(40) NOT NULL,
    auth_generation integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT uq_account__active_email UNIQUE (email_digest_v2),
    CONSTRAINT ck_account__email_digest_length CHECK (octet_length(email_digest_v2) = 32),
    CONSTRAINT ck_account__status CHECK (status IN ('pending', 'active', 'recovery', 'suspended', 'closed')),
    CONSTRAINT ck_account__auth_generation CHECK (auth_generation >= 0),
    CONSTRAINT ck_account__ciphertext_object CHECK (jsonb_typeof(email_ciphertext) = 'object')
);

CREATE TABLE iam.credential (
    account_id uuid NOT NULL,
    credential_id uuid NOT NULL,
    type varchar(40) NOT NULL,
    credential_material jsonb NOT NULL,
    sign_count bigint NOT NULL,
    status varchar(40) NOT NULL,
    CONSTRAINT pk_credential PRIMARY KEY (account_id, credential_id),
    CONSTRAINT fk_credential__account__owner FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT ck_credential__type CHECK (type IN ('passkey', 'password', 'totp')),
    CONSTRAINT ck_credential__status CHECK (status IN ('pending', 'active', 'revoked')),
    CONSTRAINT ck_credential__sign_count CHECK (sign_count >= 0),
    CONSTRAINT ck_credential__material_object CHECK (jsonb_typeof(credential_material) = 'object')
);

CREATE TABLE iam.session_record (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    token_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    auth_strength varchar(40) NOT NULL,
    auth_generation integer NOT NULL,
    authenticated_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_session_record PRIMARY KEY (id),
    CONSTRAINT fk_session_record__account__owner FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT uq_session_record__token UNIQUE (digest_key_id, token_digest),
    CONSTRAINT ck_session_record__token_length CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_session_record__auth_strength CHECK (auth_strength IN ('passkey', 'password_totp', 'recovery')),
    CONSTRAINT ck_session_record__expiry CHECK (absolute_expires_at <= authenticated_at + interval '7 days'
        AND last_seen_at <= absolute_expires_at)
);

CREATE INDEX ix_session_record__expiry_reaper ON iam.session_record (absolute_expires_at);
COMMENT ON INDEX iam.ix_session_record__expiry_reaper IS 'expiry_reaper';

CREATE TABLE iam.recovery_code (
    account_id uuid NOT NULL,
    generation integer NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    code_digest bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    CONSTRAINT pk_recovery_code PRIMARY KEY (account_id, generation, digest_key_id, code_digest),
    CONSTRAINT fk_recovery_code__account__owner FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT ck_recovery_code__digest_length CHECK (octet_length(code_digest) = 32),
    CONSTRAINT ck_recovery_code__generation CHECK (generation >= 0),
    CONSTRAINT ck_recovery_code__usage CHECK (used_at IS NULL OR used_at <= expires_at)
);

CREATE TABLE iam.auth_generation (
    principal_id uuid NOT NULL,
    scope_type varchar(40) NOT NULL,
    scope_id uuid NOT NULL,
    generation integer NOT NULL,
    changed_at timestamptz NOT NULL,
    reason varchar(80) NOT NULL,
    CONSTRAINT pk_auth_generation PRIMARY KEY (principal_id, scope_type, scope_id),
    CONSTRAINT ck_auth_generation__generation CHECK (generation >= 0)
);

CREATE TABLE iam.recovery_case (
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    method varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    requested_at timestamptz NOT NULL,
    not_before timestamptz NOT NULL,
    requested_by uuid NOT NULL,
    approved_by uuid,
    completed_at timestamptz,
    CONSTRAINT pk_recovery_case PRIMARY KEY (id),
    CONSTRAINT fk_recovery_case__account__owner FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT ck_recovery_case__method CHECK (method IN ('passkey', 'recovery_code', 'manual')),
    CONSTRAINT ck_recovery_case__status CHECK (status IN ('pending', 'approved', 'completed', 'rejected', 'expired')),
    CONSTRAINT ck_recovery_case__manual_delay CHECK (method <> 'manual' OR not_before >= requested_at + interval '72 hours'),
    CONSTRAINT ck_recovery_case__two_person CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_recovery_case__completion CHECK ((status = 'completed') = (completed_at IS NOT NULL))
);

CREATE TABLE workspace.workspace (
    id uuid NOT NULL,
    owner_account_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    status varchar(40) NOT NULL,
    billing_mode varchar(40) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT fk_workspace__account__owner FOREIGN KEY (owner_account_id) REFERENCES iam.account (id),
    CONSTRAINT ck_workspace__status CHECK (status IN ('pending', 'active', 'restricted', 'closed')),
    CONSTRAINT ck_workspace__billing_mode CHECK (billing_mode IN ('trial', 'subscription', 'external')),
    CONSTRAINT ck_workspace__name_length CHECK (char_length(name) BETWEEN 1 AND 160)
);

CREATE TABLE workspace.membership (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    role varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    joined_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_membership PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_membership__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT fk_membership__account__principal FOREIGN KEY (account_id) REFERENCES iam.account (id),
    CONSTRAINT uq_membership__workspace_account UNIQUE (workspace_id, account_id),
    CONSTRAINT ck_membership__role CHECK (role IN ('owner', 'admin', 'project_manager', 'member', 'billing_admin')),
    CONSTRAINT ck_membership__status CHECK (status IN ('invited', 'active', 'revoked')),
    CONSTRAINT ck_membership__revocation CHECK ((status = 'revoked') = (revoked_at IS NOT NULL))
);

CREATE INDEX ix_membership__tenant_list ON workspace.membership (workspace_id, status, joined_at);
COMMENT ON INDEX workspace.ix_membership__tenant_list IS 'tenant_list';

CREATE TABLE workspace.invitation (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    token_digest bytea NOT NULL,
    digest_key_id varchar(80) NOT NULL,
    email_digest_v2 bytea NOT NULL,
    role varchar(40) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT pk_invitation PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_invitation__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT uq_invitation__token UNIQUE (digest_key_id, token_digest),
    CONSTRAINT ck_invitation__token_length CHECK (octet_length(token_digest) = 32),
    CONSTRAINT ck_invitation__email_length CHECK (octet_length(email_digest_v2) = 32),
    CONSTRAINT ck_invitation__role CHECK (role IN ('admin', 'project_manager', 'member', 'billing_admin')),
    CONSTRAINT ck_invitation__terminal CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);

CREATE INDEX ix_invitation__expiry_reaper ON workspace.invitation (workspace_id, expires_at);
COMMENT ON INDEX workspace.ix_invitation__expiry_reaper IS 'expiry_reaper';

CREATE TABLE workspace.ownership_transfer (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    from_membership_id uuid NOT NULL,
    to_membership_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_ownership_transfer PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_ownership_transfer__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT fk_ownership_transfer__membership__from FOREIGN KEY (workspace_id, from_membership_id)
        REFERENCES workspace.membership (workspace_id, id),
    CONSTRAINT fk_ownership_transfer__membership__to FOREIGN KEY (workspace_id, to_membership_id)
        REFERENCES workspace.membership (workspace_id, id),
    CONSTRAINT ck_ownership_transfer__different CHECK (from_membership_id <> to_membership_id),
    CONSTRAINT ck_ownership_transfer__status CHECK (status IN ('pending', 'accepted', 'expired', 'cancelled'))
);

CREATE UNIQUE INDEX uq_ownership_transfer__active
ON workspace.ownership_transfer (workspace_id) WHERE status = 'pending';

CREATE FUNCTION workspace.assert_active_owner(affected_workspace uuid) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, workspace
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM workspace.workspace item WHERE item.id = affected_workspace)
       AND NOT EXISTS (
           SELECT 1 FROM workspace.workspace item
           JOIN workspace.membership membership
             ON membership.workspace_id = item.id
            AND membership.account_id = item.owner_account_id
            AND membership.role = 'owner'
            AND membership.status = 'active'
           WHERE item.id = affected_workspace
       ) THEN
        RAISE EXCEPTION 'workspace requires one active owner membership' USING ERRCODE = '23514';
    END IF;
END
$$;

CREATE FUNCTION workspace.require_active_owner_for_workspace() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, workspace
AS $$
BEGIN
    PERFORM workspace.assert_active_owner(NEW.id);
    RETURN NULL;
END
$$;

CREATE FUNCTION workspace.preserve_active_owner_for_membership() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, workspace
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM workspace.assert_active_owner(OLD.workspace_id);
    ELSE
        PERFORM workspace.assert_active_owner(NEW.workspace_id);
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_workspace_require_active_owner
AFTER INSERT OR UPDATE ON workspace.workspace
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION workspace.require_active_owner_for_workspace();

CREATE CONSTRAINT TRIGGER trg_membership_preserve_active_owner
AFTER INSERT OR UPDATE OR DELETE ON workspace.membership
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION workspace.preserve_active_owner_for_membership();

REVOKE ALL ON ALL TABLES IN SCHEMA iam, workspace FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA iam, workspace TO app_runtime;
