ALTER TABLE intake.request
    ADD COLUMN requester_email_ciphertext jsonb,
    ADD CONSTRAINT ck_request__email_ciphertext_object
        CHECK (requester_email_ciphertext IS NULL OR jsonb_typeof(requester_email_ciphertext) = 'object');

CREATE TABLE project.client_access (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    party_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_client_access PRIMARY KEY (workspace_id, id),
    CONSTRAINT uq_client_access__id UNIQUE (id),
    CONSTRAINT fk_client_access__project__owner FOREIGN KEY (workspace_id, project_id)
        REFERENCES project.project (workspace_id, id),
    CONSTRAINT uq_client_access__party UNIQUE (workspace_id, project_id, party_id)
);

CREATE TABLE project.external_payment_record (
    workspace_id uuid NOT NULL,
    project_id uuid NOT NULL,
    id uuid NOT NULL,
    status varchar(40) NOT NULL,
    reference_ciphertext jsonb NOT NULL,
    recorded_by uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT pk_external_payment_record PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_external_payment_record__project__owner FOREIGN KEY (workspace_id, project_id)
        REFERENCES project.project (workspace_id, id),
    CONSTRAINT ck_external_payment_record__status CHECK (
        status IN ('pending', 'invoiced', 'paid', 'refunded', 'disputed')
    ),
    CONSTRAINT ck_external_payment_record__reference CHECK (jsonb_typeof(reference_ciphertext) = 'object')
);

REVOKE ALL ON project.client_access, project.external_payment_record FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON project.client_access, project.external_payment_record TO app_runtime;
