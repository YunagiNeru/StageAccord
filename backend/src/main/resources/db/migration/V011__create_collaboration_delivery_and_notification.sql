CREATE TABLE collab.progress_update (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL,
    thread_id uuid NOT NULL, version_no integer NOT NULL, author_id uuid NOT NULL, visibility varchar(20) NOT NULL,
    body_ciphertext jsonb NOT NULL, status varchar(20) NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz,
    CONSTRAINT pk_progress_update PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_progress_update__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT fk_progress_update__checkpoint__context FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT uq_progress_update__version UNIQUE (workspace_id,thread_id,version_no),
    CONSTRAINT ck_progress_update__version CHECK (version_no > 0),
    CONSTRAINT ck_progress_update__visibility CHECK (visibility IN ('private','client')),
    CONSTRAINT ck_progress_update__body CHECK (jsonb_typeof(body_ciphertext)='object'),
    CONSTRAINT ck_progress_update__status CHECK (status IN ('draft','published','superseded')),
    CONSTRAINT ck_progress_update__published CHECK ((status='draft')=(published_at IS NULL))
);

CREATE TABLE collab.comment (
    workspace_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL, author_id uuid NOT NULL,
    body_ciphertext jsonb NOT NULL, target_type varchar(30) NOT NULL, target_id uuid NOT NULL,
    target_version integer NOT NULL, time_offset_ms bigint, position_json jsonb, created_at timestamptz NOT NULL,
    CONSTRAINT pk_comment PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_comment__checkpoint__context FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT ck_comment__body CHECK (jsonb_typeof(body_ciphertext)='object'),
    CONSTRAINT ck_comment__target_type CHECK (target_type IN ('checkpoint','file','external_link','text')),
    CONSTRAINT ck_comment__target_version CHECK (target_version > 0),
    CONSTRAINT ck_comment__time CHECK (time_offset_ms IS NULL OR time_offset_ms >= 0),
    CONSTRAINT ck_comment__position CHECK (position_json IS NULL OR jsonb_typeof(position_json)='object')
);

CREATE TABLE collab.revision_draft (
    workspace_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL, author_id uuid NOT NULL,
    body_ciphertext jsonb NOT NULL, target_type varchar(30) NOT NULL, target_id uuid NOT NULL,
    target_version integer NOT NULL, status varchar(20) NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_revision_draft PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_revision_draft__checkpoint__owner FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT ck_revision_draft__body CHECK (jsonb_typeof(body_ciphertext)='object'),
    CONSTRAINT ck_revision_draft__target_version CHECK (target_version > 0),
    CONSTRAINT ck_revision_draft__status CHECK (status IN ('draft','submitted','withdrawn'))
);

CREATE TABLE collab.revision_round (
    workspace_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL, agreement_version_id uuid NOT NULL,
    round_no integer NOT NULL, classification varchar(30) NOT NULL, item_count integer NOT NULL,
    consumes_quota boolean NOT NULL, change_order_id uuid, status varchar(20) NOT NULL, submitted_at timestamptz NOT NULL,
    CONSTRAINT pk_revision_round PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_revision_round__checkpoint__owner FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT fk_revision_round__agreement__scope FOREIGN KEY (workspace_id,agreement_version_id) REFERENCES agreement.agreement_version(workspace_id,id),
    CONSTRAINT fk_revision_round__change_order__scope FOREIGN KEY (workspace_id,change_order_id) REFERENCES agreement.change_order(workspace_id,id),
    CONSTRAINT uq_revision_round__number UNIQUE (workspace_id,checkpoint_id,round_no),
    CONSTRAINT ck_revision_round__number CHECK (round_no > 0 AND item_count > 0),
    CONSTRAINT ck_revision_round__classification CHECK (classification IN ('in_scope','correction','change_order')),
    CONSTRAINT ck_revision_round__quota CHECK (consumes_quota=(classification='in_scope')),
    CONSTRAINT ck_revision_round__change_order CHECK ((classification='change_order')=(change_order_id IS NOT NULL)),
    CONSTRAINT ck_revision_round__status CHECK (status IN ('requested','resolved','withdrawn'))
);

CREATE TABLE collab.approval_process (
    workspace_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL,
    target_type varchar(30) NOT NULL, target_id uuid NOT NULL, target_version integer NOT NULL,
    target_sha256 bytea NOT NULL, rule_json jsonb NOT NULL, required_approvals integer NOT NULL,
    status varchar(20) NOT NULL, created_at timestamptz NOT NULL, satisfied_at timestamptz,
    CONSTRAINT pk_approval_process PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_approval_process__checkpoint__owner FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT ck_approval_process__target_version CHECK (target_version > 0),
    CONSTRAINT ck_approval_process__hash CHECK (octet_length(target_sha256)=32),
    CONSTRAINT ck_approval_process__rule CHECK (jsonb_typeof(rule_json)='object' AND required_approvals > 0),
    CONSTRAINT ck_approval_process__status CHECK (status IN ('pending','approved','rejected','locked')),
    CONSTRAINT ck_approval_process__satisfied CHECK ((status='approved')=(satisfied_at IS NOT NULL))
);

CREATE TABLE collab.approval_action (
    workspace_id uuid NOT NULL, approval_id uuid NOT NULL, actor_id uuid NOT NULL,
    decision varchar(20) NOT NULL, target_sha256 bytea NOT NULL, explicit_user_action boolean NOT NULL,
    authenticated_at timestamptz NOT NULL, acted_at timestamptz NOT NULL,
    CONSTRAINT pk_approval_action PRIMARY KEY (workspace_id,approval_id,actor_id),
    CONSTRAINT fk_approval_action__process__owner FOREIGN KEY (workspace_id,approval_id) REFERENCES collab.approval_process(workspace_id,id),
    CONSTRAINT ck_approval_action__decision CHECK (decision IN ('approved','rejected')),
    CONSTRAINT ck_approval_action__hash CHECK (octet_length(target_sha256)=32),
    CONSTRAINT ck_approval_action__explicit CHECK (explicit_user_action),
    CONSTRAINT ck_approval_action__fresh CHECK (acted_at <= authenticated_at + interval '30 minutes')
);

CREATE TABLE collab.delivery_package (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, agreement_version_id uuid NOT NULL,
    package_no integer NOT NULL, manifest_json jsonb NOT NULL, terms_ciphertext jsonb NOT NULL,
    credits_ciphertext jsonb NOT NULL, notes_ciphertext jsonb NOT NULL, status varchar(20) NOT NULL,
    prepared_at timestamptz NOT NULL, frozen_at timestamptz, delivered_at timestamptz,
    CONSTRAINT pk_delivery_package PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_delivery_package__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT fk_delivery_package__agreement__scope FOREIGN KEY (workspace_id,agreement_version_id) REFERENCES agreement.agreement_version(workspace_id,id),
    CONSTRAINT uq_delivery_package__number UNIQUE (workspace_id,project_id,package_no),
    CONSTRAINT ck_delivery_package__number CHECK (package_no > 0),
    CONSTRAINT ck_delivery_package__json CHECK (jsonb_typeof(manifest_json)='object' AND jsonb_typeof(terms_ciphertext)='object' AND jsonb_typeof(credits_ciphertext)='object' AND jsonb_typeof(notes_ciphertext)='object'),
    CONSTRAINT ck_delivery_package__status CHECK (status IN ('prepared','fixed','delivered','received','issue_reported')),
    CONSTRAINT ck_delivery_package__frozen CHECK ((status='prepared')=(frozen_at IS NULL)),
    CONSTRAINT ck_delivery_package__delivered CHECK ((status IN ('delivered','received','issue_reported'))=(delivered_at IS NOT NULL))
);

CREATE TABLE collab.delivery_item (
    workspace_id uuid NOT NULL, package_id uuid NOT NULL, file_version_id uuid NOT NULL, ordinal integer NOT NULL,
    label varchar(240) NOT NULL, sha256 bytea NOT NULL, size_bytes bigint NOT NULL,
    CONSTRAINT pk_delivery_item PRIMARY KEY (workspace_id,package_id,file_version_id),
    CONSTRAINT fk_delivery_item__package__owner FOREIGN KEY (workspace_id,package_id) REFERENCES collab.delivery_package(workspace_id,id),
    CONSTRAINT fk_delivery_item__file_version__target FOREIGN KEY (workspace_id,file_version_id) REFERENCES file_store.file_version(workspace_id,id),
    CONSTRAINT uq_delivery_item__ordinal UNIQUE (workspace_id,package_id,ordinal),
    CONSTRAINT ck_delivery_item__ordinal CHECK (ordinal > 0 AND size_bytes > 0),
    CONSTRAINT ck_delivery_item__hash CHECK (octet_length(sha256)=32)
);

CREATE TABLE collab.delivery_receipt (
    workspace_id uuid NOT NULL, package_id uuid NOT NULL, actor_id uuid NOT NULL,
    decision varchar(20) NOT NULL, package_manifest_sha256 bytea NOT NULL, explicit_user_action boolean NOT NULL,
    authenticated_at timestamptz NOT NULL, received_at timestamptz NOT NULL,
    CONSTRAINT pk_delivery_receipt PRIMARY KEY (workspace_id,package_id,actor_id),
    CONSTRAINT fk_delivery_receipt__package__target FOREIGN KEY (workspace_id,package_id) REFERENCES collab.delivery_package(workspace_id,id),
    CONSTRAINT ck_delivery_receipt__decision CHECK (decision IN ('received','issue_reported')),
    CONSTRAINT ck_delivery_receipt__hash CHECK (octet_length(package_manifest_sha256)=32),
    CONSTRAINT ck_delivery_receipt__explicit CHECK (explicit_user_action),
    CONSTRAINT ck_delivery_receipt__fresh CHECK (received_at <= authenticated_at + interval '30 minutes')
);

CREATE TABLE schedule.notification_preference (
    workspace_id uuid NOT NULL, principal_id uuid NOT NULL, category varchar(40) NOT NULL,
    channel varchar(20) NOT NULL, mode varchar(20) NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_preference PRIMARY KEY (workspace_id,principal_id,category,channel),
    CONSTRAINT fk_notification_preference__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_notification_preference__category CHECK (category IN ('security','transaction','activity')),
    CONSTRAINT ck_notification_preference__channel CHECK (channel IN ('in_app','email')),
    CONSTRAINT ck_notification_preference__mode CHECK (mode IN ('immediate','digest','disabled')),
    CONSTRAINT ck_notification_preference__mandatory CHECK (category='activity' OR mode<>'disabled')
);

CREATE TABLE schedule.notification_request (
    workspace_id uuid NOT NULL, project_id uuid, id uuid NOT NULL, principal_id uuid NOT NULL,
    event_id uuid NOT NULL, category varchar(40) NOT NULL, template_key varchar(120) NOT NULL,
    template_data jsonb NOT NULL, status varchar(20) NOT NULL, created_at timestamptz NOT NULL,
    CONSTRAINT pk_notification_request PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_notification_request__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT fk_notification_request__project__scope FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT uq_notification_request__event_recipient UNIQUE (workspace_id,event_id,principal_id,template_key),
    CONSTRAINT ck_notification_request__data CHECK (jsonb_typeof(template_data)='object'),
    CONSTRAINT ck_notification_request__status CHECK (status IN ('queued','sent','retry','dead_letter'))
);

CREATE TABLE schedule.notification_delivery (
    workspace_id uuid NOT NULL, request_id uuid NOT NULL, channel varchar(20) NOT NULL,
    destination_digest bytea NOT NULL, provider_message_id varchar(255), result varchar(20) NOT NULL,
    attempted_at timestamptz NOT NULL, error_code varchar(80),
    CONSTRAINT pk_notification_delivery PRIMARY KEY (workspace_id,request_id,channel,attempted_at),
    CONSTRAINT fk_notification_delivery__request__owner FOREIGN KEY (workspace_id,request_id) REFERENCES schedule.notification_request(workspace_id,id),
    CONSTRAINT ck_notification_delivery__digest CHECK (octet_length(destination_digest)=32),
    CONSTRAINT ck_notification_delivery__result CHECK (result IN ('sent','retry','failed')),
    CONSTRAINT ck_notification_delivery__error CHECK ((result='sent')=(error_code IS NULL))
);

CREATE FUNCTION collab.reject_evidence_mutation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,collab AS $$ BEGIN RAISE EXCEPTION 'collaboration evidence is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER trg_comment_append_only BEFORE UPDATE OR DELETE ON collab.comment FOR EACH ROW EXECUTE FUNCTION collab.reject_evidence_mutation();
CREATE TRIGGER trg_revision_round_append_only BEFORE UPDATE OR DELETE ON collab.revision_round FOR EACH ROW EXECUTE FUNCTION collab.reject_evidence_mutation();
CREATE TRIGGER trg_approval_action_append_only BEFORE UPDATE OR DELETE ON collab.approval_action FOR EACH ROW EXECUTE FUNCTION collab.reject_evidence_mutation();
CREATE TRIGGER trg_delivery_item_append_only BEFORE UPDATE OR DELETE ON collab.delivery_item FOR EACH ROW EXECUTE FUNCTION collab.reject_evidence_mutation();
CREATE TRIGGER trg_delivery_receipt_append_only BEFORE UPDATE OR DELETE ON collab.delivery_receipt FOR EACH ROW EXECUTE FUNCTION collab.reject_evidence_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA collab,schedule FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA collab,schedule TO app_runtime;
REVOKE UPDATE,DELETE ON collab.comment,collab.revision_round,collab.approval_action,collab.delivery_item,collab.delivery_receipt FROM app_runtime;
