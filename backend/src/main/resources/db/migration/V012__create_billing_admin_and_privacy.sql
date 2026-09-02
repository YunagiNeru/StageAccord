CREATE TABLE billing.customer (
    workspace_id uuid NOT NULL, id uuid NOT NULL, stripe_customer_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL, status varchar(20) NOT NULL,
    CONSTRAINT pk_billing_customer PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_billing_customer__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT uq_billing_customer__workspace UNIQUE (workspace_id),
    CONSTRAINT uq_billing_customer__stripe UNIQUE (stripe_customer_id),
    CONSTRAINT ck_billing_customer__status CHECK (status IN ('active','deleted'))
);

CREATE TABLE billing.subscription (
    workspace_id uuid NOT NULL, id uuid NOT NULL, customer_id uuid NOT NULL,
    stripe_subscription_id varchar(255) NOT NULL, plan_key varchar(80) NOT NULL,
    pending_plan_key varchar(80), status varchar(20) NOT NULL, current_period_end timestamptz NOT NULL,
    payment_failed_at timestamptz, provider_updated_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_subscription PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_subscription__customer__owner FOREIGN KEY (workspace_id,customer_id) REFERENCES billing.customer(workspace_id,id),
    CONSTRAINT uq_subscription__stripe UNIQUE (stripe_subscription_id),
    CONSTRAINT ck_subscription__status CHECK (status IN ('trial','active','grace','restricted','cancelled')),
    CONSTRAINT ck_subscription__payment_failure CHECK ((status IN ('grace','restricted'))=(payment_failed_at IS NOT NULL))
);

CREATE TABLE billing.stripe_event (
    workspace_id uuid NOT NULL, id uuid NOT NULL, stripe_event_id varchar(255) NOT NULL,
    stripe_created_at timestamptz NOT NULL, event_type varchar(160) NOT NULL, payload_sha256 bytea NOT NULL,
    signature_verified boolean NOT NULL, api_verified boolean NOT NULL, received_at timestamptz NOT NULL,
    applied_at timestamptz, result varchar(20) NOT NULL,
    CONSTRAINT pk_stripe_event PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_stripe_event__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT uq_stripe_event__provider_id UNIQUE (stripe_event_id),
    CONSTRAINT ck_stripe_event__hash CHECK (octet_length(payload_sha256)=32),
    CONSTRAINT ck_stripe_event__verified CHECK (signature_verified AND api_verified),
    CONSTRAINT ck_stripe_event__result CHECK (result IN ('applied','stale','duplicate','rejected')),
    CONSTRAINT ck_stripe_event__applied CHECK ((result='applied')=(applied_at IS NOT NULL))
);

CREATE TABLE billing.entitlement (
    workspace_id uuid NOT NULL, id uuid NOT NULL, subscription_id uuid NOT NULL,
    state varchar(20) NOT NULL, limits_json jsonb NOT NULL, provider_updated_at timestamptz NOT NULL,
    reconciled_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_entitlement PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_entitlement__subscription__source FOREIGN KEY (workspace_id,subscription_id) REFERENCES billing.subscription(workspace_id,id),
    CONSTRAINT uq_entitlement__subscription UNIQUE (workspace_id,subscription_id),
    CONSTRAINT ck_entitlement__state CHECK (state IN ('trial','active','grace','restricted','cancelled')),
    CONSTRAINT ck_entitlement__limits CHECK (jsonb_typeof(limits_json)='object')
);

CREATE TABLE billing.usage_ledger (
    workspace_id uuid NOT NULL, id uuid NOT NULL, metric varchar(40) NOT NULL, delta bigint NOT NULL,
    aggregate_type varchar(80) NOT NULL, aggregate_id uuid NOT NULL, event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT pk_usage_ledger PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_usage_ledger__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT uq_usage_ledger__event_metric UNIQUE (workspace_id,event_id,metric),
    CONSTRAINT ck_usage_ledger__metric CHECK (metric IN ('active_projects','members','storage_bytes','templates'))
);

CREATE TABLE audit.report (
    workspace_id uuid NOT NULL, id uuid NOT NULL, reporter_id uuid NOT NULL, subject_type varchar(40) NOT NULL,
    subject_id uuid NOT NULL, reason_code varchar(80) NOT NULL, detail_ciphertext jsonb NOT NULL,
    status varchar(20) NOT NULL, created_at timestamptz NOT NULL, resolved_at timestamptz,
    CONSTRAINT pk_report PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_report__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_report__detail CHECK (jsonb_typeof(detail_ciphertext)='object'),
    CONSTRAINT ck_report__status CHECK (status IN ('open','investigating','resolved','dismissed')),
    CONSTRAINT ck_report__resolved CHECK ((status IN ('resolved','dismissed'))=(resolved_at IS NOT NULL))
);

CREATE TABLE audit.subject_block (
    workspace_id uuid NOT NULL, blocker_id uuid NOT NULL, blocked_subject_digest bytea NOT NULL,
    reason_code varchar(80) NOT NULL, created_at timestamptz NOT NULL, revoked_at timestamptz,
    CONSTRAINT pk_subject_block PRIMARY KEY (workspace_id,blocker_id,blocked_subject_digest),
    CONSTRAINT fk_subject_block__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_subject_block__digest CHECK (octet_length(blocked_subject_digest)=32)
);

CREATE TABLE audit.support_grant (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, ticket_id varchar(120) NOT NULL,
    purpose varchar(240) NOT NULL, allowed_operations jsonb NOT NULL, requester_id uuid NOT NULL,
    approver_id uuid NOT NULL, approved_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_support_grant PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_support_grant__project__target FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_support_grant__operations CHECK (jsonb_typeof(allowed_operations)='array'),
    CONSTRAINT ck_support_grant__separation CHECK (requester_id<>approver_id),
    CONSTRAINT ck_support_grant__expiry CHECK (expires_at>approved_at AND expires_at<=approved_at+interval '60 minutes')
);

CREATE TABLE audit.kill_switch_event (
    workspace_id uuid NOT NULL, id uuid NOT NULL, feature varchar(40) NOT NULL, action varchar(20) NOT NULL,
    reason varchar(240) NOT NULL, initiated_by uuid NOT NULL, confirmed_by uuid,
    authenticated_at timestamptz NOT NULL, occurred_at timestamptz NOT NULL, release_condition varchar(240) NOT NULL,
    CONSTRAINT pk_kill_switch_event PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_kill_switch_event__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_kill_switch_event__feature CHECK (feature IN ('authentication','intake','upload','critical_writes')),
    CONSTRAINT ck_kill_switch_event__action CHECK (action IN ('stopped','released')),
    CONSTRAINT ck_kill_switch_event__confirmation CHECK ((action='released')=(confirmed_by IS NOT NULL)),
    CONSTRAINT ck_kill_switch_event__separation CHECK (confirmed_by IS NULL OR initiated_by<>confirmed_by),
    CONSTRAINT ck_kill_switch_event__fresh CHECK (occurred_at<=authenticated_at+interval '30 minutes')
);

CREATE TABLE privacy.deletion_request (
    workspace_id uuid NOT NULL, id uuid NOT NULL, subject_id uuid NOT NULL, status varchar(30) NOT NULL,
    requested_at timestamptz NOT NULL, ledger_acked_at timestamptz, processing_started_at timestamptz,
    completed_at timestamptz, cache_due_at timestamptz NOT NULL, primary_due_at timestamptz NOT NULL,
    backup_due_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_deletion_request PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_deletion_request__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_deletion_request__status CHECK (status IN ('created','ledger_pending','ledger_acked','processing','completed','held','failed')),
    CONSTRAINT ck_deletion_request__deadlines CHECK (cache_due_at<=requested_at+interval '24 hours' AND primary_due_at<=requested_at+interval '30 days' AND backup_due_at<=requested_at+interval '35 days')
);

CREATE TABLE privacy.deletion_target (
    workspace_id uuid NOT NULL, request_id uuid NOT NULL, target_type varchar(40) NOT NULL,
    target_digest bytea NOT NULL, status varchar(20) NOT NULL, deleted_at timestamptz, detail_json jsonb NOT NULL,
    CONSTRAINT pk_deletion_target PRIMARY KEY (workspace_id,request_id,target_type,target_digest),
    CONSTRAINT fk_deletion_target__request__owner FOREIGN KEY (workspace_id,request_id) REFERENCES privacy.deletion_request(workspace_id,id),
    CONSTRAINT ck_deletion_target__digest CHECK (octet_length(target_digest)=32),
    CONSTRAINT ck_deletion_target__status CHECK (status IN ('pending','deleted','held','failed')),
    CONSTRAINT ck_deletion_target__deleted CHECK ((status='deleted')=(deleted_at IS NOT NULL)),
    CONSTRAINT ck_deletion_target__detail CHECK (jsonb_typeof(detail_json)='object')
);

CREATE TABLE privacy.legal_hold (
    workspace_id uuid NOT NULL, id uuid NOT NULL, target_digest bytea NOT NULL, reason varchar(240) NOT NULL,
    placed_by uuid NOT NULL, placed_at timestamptz NOT NULL, released_by uuid, released_at timestamptz,
    CONSTRAINT pk_legal_hold PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_legal_hold__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_legal_hold__digest CHECK (octet_length(target_digest)=32),
    CONSTRAINT ck_legal_hold__release CHECK ((released_by IS NULL)=(released_at IS NULL))
);

CREATE TABLE privacy.data_export (
    workspace_id uuid NOT NULL, id uuid NOT NULL, subject_id uuid NOT NULL, file_version_id uuid,
    format varchar(20) NOT NULL, status varchar(20) NOT NULL, requested_at timestamptz NOT NULL,
    completed_at timestamptz, expires_at timestamptz,
    CONSTRAINT pk_data_export PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_data_export__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT fk_data_export__file__result FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT ck_data_export__format CHECK (format IN ('html','json')),
    CONSTRAINT ck_data_export__status CHECK (status IN ('queued','processing','ready','failed','expired')),
    CONSTRAINT ck_data_export__ready CHECK ((status='ready')=(completed_at IS NOT NULL AND expires_at IS NOT NULL))
);

CREATE TABLE privacy.restoration_ledger_mirror (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, entry_id uuid NOT NULL UNIQUE,
    action varchar(30) NOT NULL, subject_digest bytea NOT NULL, payload_json jsonb NOT NULL,
    previous_hash bytea NOT NULL, entry_hash bytea NOT NULL, key_id varchar(80) NOT NULL,
    signature bytea NOT NULL, occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_restoration_ledger__action CHECK (action IN ('delete','unpublish','revoke_access','legal_hold','release_hold')),
    CONSTRAINT ck_restoration_ledger__digest CHECK (octet_length(subject_digest)=32),
    CONSTRAINT ck_restoration_ledger__payload CHECK (jsonb_typeof(payload_json)='object'),
    CONSTRAINT ck_restoration_ledger__hashes CHECK (octet_length(previous_hash)=32 AND octet_length(entry_hash)=32),
    CONSTRAINT ck_restoration_ledger__signature CHECK (octet_length(signature)=64)
);

CREATE FUNCTION audit.reject_admin_evidence_mutation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,audit AS $$ BEGIN RAISE EXCEPTION 'admin evidence is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER trg_kill_switch_event_append_only BEFORE UPDATE OR DELETE ON audit.kill_switch_event FOR EACH ROW EXECUTE FUNCTION audit.reject_admin_evidence_mutation();
CREATE FUNCTION billing.reject_billing_evidence_mutation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,billing AS $$ BEGIN RAISE EXCEPTION 'billing evidence is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER trg_stripe_event_append_only BEFORE UPDATE OR DELETE ON billing.stripe_event FOR EACH ROW EXECUTE FUNCTION billing.reject_billing_evidence_mutation();
CREATE TRIGGER trg_usage_ledger_append_only BEFORE UPDATE OR DELETE ON billing.usage_ledger FOR EACH ROW EXECUTE FUNCTION billing.reject_billing_evidence_mutation();
CREATE FUNCTION privacy.reject_restoration_ledger_mutation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,privacy AS $$ BEGIN RAISE EXCEPTION 'restoration ledger is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER trg_restoration_ledger_append_only BEFORE UPDATE OR DELETE ON privacy.restoration_ledger_mirror FOR EACH ROW EXECUTE FUNCTION privacy.reject_restoration_ledger_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA billing,privacy FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA billing,privacy TO app_runtime;
GRANT SELECT,INSERT,UPDATE ON audit.report,audit.subject_block,audit.support_grant,audit.kill_switch_event TO app_runtime;
REVOKE UPDATE,DELETE ON billing.stripe_event,billing.usage_ledger,privacy.restoration_ledger_mirror,audit.kill_switch_event FROM app_runtime;
