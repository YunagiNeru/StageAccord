CREATE TABLE catalog.creator_profile (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    draft_json jsonb NOT NULL,
    published_version_id uuid,
    intake_status varchar(40) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_creator_profile PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_creator_profile__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT uq_creator_profile__slug UNIQUE (slug),
    CONSTRAINT ck_creator_profile__slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_creator_profile__draft_object CHECK (jsonb_typeof(draft_json) = 'object'),
    CONSTRAINT ck_creator_profile__intake_status CHECK (intake_status IN ('open', 'paused', 'closed'))
);

CREATE TABLE catalog.public_profile_projection (
    workspace_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    published_version_id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    public_json jsonb NOT NULL,
    published_at timestamptz NOT NULL,
    CONSTRAINT pk_public_profile_projection PRIMARY KEY (workspace_id, profile_id),
    CONSTRAINT fk_public_profile_projection__profile__owner FOREIGN KEY (workspace_id, profile_id)
        REFERENCES catalog.creator_profile (workspace_id, id),
    CONSTRAINT uq_public_profile_projection__slug UNIQUE (slug),
    CONSTRAINT ck_public_profile_projection__json_object CHECK (jsonb_typeof(public_json) = 'object')
);

CREATE TABLE catalog.service (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    profile_id uuid NOT NULL,
    slug varchar(100) NOT NULL,
    current_version_id uuid,
    status varchar(40) NOT NULL,
    CONSTRAINT pk_service PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_service__profile__owner FOREIGN KEY (workspace_id, profile_id)
        REFERENCES catalog.creator_profile (workspace_id, id),
    CONSTRAINT uq_service__workspace_slug UNIQUE (workspace_id, slug),
    CONSTRAINT ck_service__status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_service__slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE catalog.workflow_template_version (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    template_id uuid NOT NULL,
    version_no integer NOT NULL,
    name varchar(160) NOT NULL,
    status varchar(40) NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_workflow_template_version PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_workflow_template_version__workspace__tenant FOREIGN KEY (workspace_id)
        REFERENCES workspace.workspace (id),
    CONSTRAINT uq_workflow_template_version__version UNIQUE (workspace_id, template_id, version_no),
    CONSTRAINT ck_workflow_template_version__version CHECK (version_no > 0),
    CONSTRAINT ck_workflow_template_version__status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_workflow_template_version__publication CHECK ((status = 'draft') = (published_at IS NULL))
);

CREATE TABLE catalog.checkpoint_template (
    workspace_id uuid NOT NULL,
    workflow_version_id uuid NOT NULL,
    id uuid NOT NULL,
    sequence_no integer NOT NULL,
    type varchar(40) NOT NULL,
    duration_days integer NOT NULL,
    client_due_days integer NOT NULL,
    revision_policy_json jsonb NOT NULL,
    CONSTRAINT pk_checkpoint_template PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_checkpoint_template__workflow__owner FOREIGN KEY (workspace_id, workflow_version_id)
        REFERENCES catalog.workflow_template_version (workspace_id, id),
    CONSTRAINT uq_checkpoint_template__sequence UNIQUE (workspace_id, workflow_version_id, sequence_no),
    CONSTRAINT ck_checkpoint_template__sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_checkpoint_template__duration CHECK (duration_days >= 0 AND client_due_days >= 0),
    CONSTRAINT ck_checkpoint_template__policy_object CHECK (jsonb_typeof(revision_policy_json) = 'object')
);

CREATE TABLE catalog.service_version (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    service_id uuid NOT NULL,
    version_no integer NOT NULL,
    content_json jsonb NOT NULL,
    workflow_version_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_service_version PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_service_version__service__owner FOREIGN KEY (workspace_id, service_id)
        REFERENCES catalog.service (workspace_id, id),
    CONSTRAINT fk_service_version__workflow__snapshot FOREIGN KEY (workspace_id, workflow_version_id)
        REFERENCES catalog.workflow_template_version (workspace_id, id),
    CONSTRAINT uq_service_version__version UNIQUE (workspace_id, service_id, version_no),
    CONSTRAINT ck_service_version__version CHECK (version_no > 0),
    CONSTRAINT ck_service_version__content_object CHECK (jsonb_typeof(content_json) = 'object'),
    CONSTRAINT ck_service_version__status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_service_version__publication CHECK ((status = 'draft') = (published_at IS NULL))
);

CREATE TABLE catalog.intake_form_version (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    service_version_id uuid NOT NULL,
    version_no integer NOT NULL,
    schema_json jsonb NOT NULL,
    privacy_text_version varchar(80) NOT NULL,
    status varchar(40) NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_intake_form_version PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_intake_form_version__service_version__owner FOREIGN KEY (workspace_id, service_version_id)
        REFERENCES catalog.service_version (workspace_id, id),
    CONSTRAINT uq_intake_form_version__version UNIQUE (workspace_id, service_version_id, version_no),
    CONSTRAINT ck_intake_form_version__schema_object CHECK (jsonb_typeof(schema_json) = 'object'),
    CONSTRAINT ck_intake_form_version__status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_intake_form_version__publication CHECK ((status = 'draft') = (published_at IS NULL))
);

CREATE TABLE intake.request (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    service_version_id uuid NOT NULL,
    form_version_id uuid NOT NULL,
    requester_email_digest_v2 bytea NOT NULL,
    privacy_text_version varchar(80) NOT NULL,
    status varchar(40) NOT NULL,
    submitted_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_request PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_request__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT fk_request__service_version__snapshot FOREIGN KEY (workspace_id, service_version_id)
        REFERENCES catalog.service_version (workspace_id, id),
    CONSTRAINT fk_request__form_version__snapshot FOREIGN KEY (workspace_id, form_version_id)
        REFERENCES catalog.intake_form_version (workspace_id, id),
    CONSTRAINT ck_request__email_digest_length CHECK (octet_length(requester_email_digest_v2) = 32),
    CONSTRAINT ck_request__status CHECK (status IN ('submitted', 'screening', 'clarification', 'accepted', 'declined', 'withdrawn'))
);

CREATE INDEX ix_request__tenant_list ON intake.request (workspace_id, status, submitted_at);
COMMENT ON INDEX intake.ix_request__tenant_list IS 'tenant_list';

CREATE TABLE intake.request_answer (
    workspace_id uuid NOT NULL,
    request_id uuid NOT NULL,
    field_id varchar(100) NOT NULL,
    answer_json jsonb NOT NULL,
    sensitivity_class varchar(40) NOT NULL,
    CONSTRAINT pk_request_answer PRIMARY KEY (workspace_id, request_id, field_id),
    CONSTRAINT fk_request_answer__request__owner FOREIGN KEY (workspace_id, request_id)
        REFERENCES intake.request (workspace_id, id),
    CONSTRAINT ck_request_answer__sensitivity CHECK (sensitivity_class IN ('standard', 'personal', 'restricted'))
);

CREATE TABLE intake.screening_event (
    workspace_id uuid NOT NULL,
    request_id uuid NOT NULL,
    id uuid NOT NULL,
    classification varchar(40) NOT NULL,
    reason_code varchar(80) NOT NULL,
    actor_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT pk_screening_event PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_screening_event__request__owner FOREIGN KEY (workspace_id, request_id)
        REFERENCES intake.request (workspace_id, id)
);

CREATE TABLE intake.sender_block (
    workspace_id uuid NOT NULL,
    id uuid NOT NULL,
    subject_digest bytea NOT NULL,
    scope varchar(40) NOT NULL,
    reason_code varchar(80) NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_sender_block PRIMARY KEY (workspace_id, id),
    CONSTRAINT fk_sender_block__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT uq_sender_block__active_scope UNIQUE (workspace_id, subject_digest, scope),
    CONSTRAINT ck_sender_block__digest_length CHECK (octet_length(subject_digest) = 32)
);

CREATE TABLE intake.intake_rate_bucket (
    workspace_id uuid NOT NULL,
    bucket_digest bytea NOT NULL,
    window_start timestamptz NOT NULL,
    count integer NOT NULL,
    blocked_until timestamptz,
    CONSTRAINT pk_intake_rate_bucket PRIMARY KEY (workspace_id, bucket_digest, window_start),
    CONSTRAINT fk_intake_rate_bucket__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace (id),
    CONSTRAINT ck_intake_rate_bucket__digest_length CHECK (octet_length(bucket_digest) = 32),
    CONSTRAINT ck_intake_rate_bucket__count CHECK (count >= 0)
);

CREATE FUNCTION catalog.reject_published_snapshot_mutation() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, catalog
AS $$
BEGIN
    IF OLD.status IN ('published', 'archived') THEN
        RAISE EXCEPTION 'published snapshot is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_service_version_reject_published_mutation
BEFORE UPDATE OR DELETE ON catalog.service_version
FOR EACH ROW EXECUTE FUNCTION catalog.reject_published_snapshot_mutation();

CREATE TRIGGER trg_workflow_version_reject_published_mutation
BEFORE UPDATE OR DELETE ON catalog.workflow_template_version
FOR EACH ROW EXECUTE FUNCTION catalog.reject_published_snapshot_mutation();

CREATE TRIGGER trg_form_version_reject_published_mutation
BEFORE UPDATE OR DELETE ON catalog.intake_form_version
FOR EACH ROW EXECUTE FUNCTION catalog.reject_published_snapshot_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA catalog, intake FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA catalog, intake TO app_runtime;
