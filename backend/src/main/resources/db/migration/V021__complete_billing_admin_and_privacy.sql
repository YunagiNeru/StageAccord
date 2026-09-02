ALTER TABLE billing.customer ADD CONSTRAINT uq_billing_customer__id UNIQUE (id);
ALTER TABLE billing.subscription ADD CONSTRAINT uq_subscription__id UNIQUE (id);
ALTER TABLE billing.entitlement ADD CONSTRAINT uq_entitlement__id UNIQUE (id);
ALTER TABLE audit.report ADD CONSTRAINT uq_report__id UNIQUE (id);
ALTER TABLE audit.support_grant ADD CONSTRAINT uq_support_grant__id UNIQUE (id);
ALTER TABLE audit.kill_switch_event ADD CONSTRAINT uq_kill_switch_event__id UNIQUE (id);
ALTER TABLE privacy.deletion_request ADD CONSTRAINT uq_deletion_request__id UNIQUE (id);
ALTER TABLE privacy.deletion_request
    ADD COLUMN processing_attempts integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_deletion_request__attempts CHECK (processing_attempts BETWEEN 0 AND 8);
ALTER TABLE privacy.data_export ADD CONSTRAINT uq_data_export__id UNIQUE (id);
ALTER TABLE privacy.data_export
    ADD COLUMN payload_json jsonb,
    ADD CONSTRAINT ck_data_export__payload CHECK (payload_json IS NULL OR jsonb_typeof(payload_json)='object');

CREATE TABLE audit.operator (
    account_id uuid PRIMARY KEY REFERENCES iam.account(id),
    role varchar(40) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_operator__role CHECK (role IN ('support','security','privacy','administrator')),
    CONSTRAINT ck_operator__status CHECK (status IN ('active','revoked'))
);

CREATE TABLE audit.support_request (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    project_id uuid NOT NULL,
    ticket_id varchar(120) NOT NULL,
    purpose varchar(240) NOT NULL,
    allowed_operations jsonb NOT NULL,
    requester_id uuid NOT NULL REFERENCES audit.operator(account_id),
    status varchar(20) NOT NULL,
    requested_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT fk_support_request__project__target FOREIGN KEY (workspace_id,project_id)
        REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_support_request__operations CHECK (jsonb_typeof(allowed_operations)='array'),
    CONSTRAINT ck_support_request__status CHECK (status IN ('requested','approved','rejected')),
    CONSTRAINT ck_support_request__decision CHECK ((status='requested')=(decided_at IS NULL))
);

REVOKE ALL ON audit.operator,audit.support_request FROM PUBLIC;
GRANT SELECT ON audit.operator TO app_runtime;
GRANT SELECT,INSERT,UPDATE ON audit.support_request TO app_runtime;
GRANT DELETE ON catalog.public_profile_projection,catalog.public_service_projection TO app_runtime;
