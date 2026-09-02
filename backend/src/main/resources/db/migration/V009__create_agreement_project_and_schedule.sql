CREATE TABLE agreement.offer (
    workspace_id uuid NOT NULL, id uuid NOT NULL, request_id uuid NOT NULL,
    service_version_id uuid NOT NULL, status varchar(40) NOT NULL, expires_at timestamptz NOT NULL,
    CONSTRAINT pk_offer PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_offer__request__source FOREIGN KEY (workspace_id,request_id) REFERENCES intake.request(workspace_id,id),
    CONSTRAINT fk_offer__service_version__snapshot FOREIGN KEY (workspace_id,service_version_id) REFERENCES catalog.service_version(workspace_id,id),
    CONSTRAINT ck_offer__status CHECK (status IN ('draft','offered','accepted','expired','withdrawn'))
);

CREATE TABLE agreement.agreement_version (
    workspace_id uuid NOT NULL, id uuid NOT NULL, offer_id uuid NOT NULL, version_no integer NOT NULL,
    canonical_json jsonb NOT NULL, canonical_sha256 bytea NOT NULL, renderer_version varchar(80) NOT NULL,
    locale varchar(20) NOT NULL, time_zone varchar(80) NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_agreement_version PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_agreement_version__offer__owner FOREIGN KEY (workspace_id,offer_id) REFERENCES agreement.offer(workspace_id,id),
    CONSTRAINT uq_agreement_version__version UNIQUE (workspace_id,offer_id,version_no),
    CONSTRAINT ck_agreement_version__version CHECK (version_no > 0),
    CONSTRAINT ck_agreement_version__hash_length CHECK (octet_length(canonical_sha256)=32),
    CONSTRAINT ck_agreement_version__json_object CHECK (jsonb_typeof(canonical_json)='object'),
    CONSTRAINT ck_agreement_version__status CHECK (status IN ('draft','offered','accepted','superseded','expired'))
);

CREATE TABLE agreement.agreement_party (
    workspace_id uuid NOT NULL, agreement_version_id uuid NOT NULL, party_id uuid NOT NULL,
    party_type varchar(40) NOT NULL, role varchar(40) NOT NULL, email_digest_v2 bytea NOT NULL, eligible boolean NOT NULL,
    CONSTRAINT pk_agreement_party PRIMARY KEY (workspace_id,agreement_version_id,party_id),
    CONSTRAINT fk_agreement_party__version__owner FOREIGN KEY (workspace_id,agreement_version_id) REFERENCES agreement.agreement_version(workspace_id,id),
    CONSTRAINT ck_agreement_party__digest_length CHECK (octet_length(email_digest_v2)=32)
);

CREATE TABLE agreement.party_acceptance (
    workspace_id uuid NOT NULL, agreement_version_id uuid NOT NULL, party_id uuid NOT NULL,
    party_role varchar(40) NOT NULL, action varchar(40) NOT NULL, authenticated_at timestamptz NOT NULL,
    accepted_at timestamptz NOT NULL, target_hash bytea NOT NULL,
    CONSTRAINT pk_party_acceptance PRIMARY KEY (workspace_id,agreement_version_id,party_id),
    CONSTRAINT fk_party_acceptance__party__actor FOREIGN KEY (workspace_id,agreement_version_id,party_id)
        REFERENCES agreement.agreement_party(workspace_id,agreement_version_id,party_id),
    CONSTRAINT ck_party_acceptance__action CHECK (action IN ('accepted','declined')),
    CONSTRAINT ck_party_acceptance__hash_length CHECK (octet_length(target_hash)=32),
    CONSTRAINT ck_party_acceptance__fresh CHECK (accepted_at <= authenticated_at + interval '30 minutes')
);

CREATE TABLE agreement.change_order (
    workspace_id uuid NOT NULL, id uuid NOT NULL, project_id uuid NOT NULL,
    base_agreement_version_id uuid NOT NULL, diff_json jsonb NOT NULL, reason text NOT NULL,
    schedule_impact_json jsonb NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_change_order PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_change_order__agreement_version__base FOREIGN KEY (workspace_id,base_agreement_version_id)
        REFERENCES agreement.agreement_version(workspace_id,id),
    CONSTRAINT ck_change_order__diff_object CHECK (jsonb_typeof(diff_json)='object'),
    CONSTRAINT ck_change_order__schedule_object CHECK (jsonb_typeof(schedule_impact_json)='object'),
    CONSTRAINT ck_change_order__status CHECK (status IN ('proposed','accepted','declined','withdrawn'))
);

CREATE TABLE project.project (
    workspace_id uuid NOT NULL, id uuid NOT NULL, agreement_version_id uuid NOT NULL,
    status varchar(40) NOT NULL, waiting_on varchar(40) NOT NULL, current_checkpoint_id uuid,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_project PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_project__agreement_version__source FOREIGN KEY (workspace_id,agreement_version_id)
        REFERENCES agreement.agreement_version(workspace_id,id),
    CONSTRAINT uq_project__agreement UNIQUE (workspace_id,agreement_version_id),
    CONSTRAINT ck_project__status CHECK (status IN ('planned','active','on_hold','disputed','cancelling','cancelled','completed')),
    CONSTRAINT ck_project__waiting_on CHECK (waiting_on IN ('NONE','CREATOR','CLIENT'))
);

ALTER TABLE agreement.change_order ADD CONSTRAINT fk_change_order__project__owner
    FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id);

CREATE TABLE project.workflow_instance (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL,
    template_version_id uuid NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_workflow_instance PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_workflow_instance__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT fk_workflow_instance__template__snapshot FOREIGN KEY (workspace_id,template_version_id)
        REFERENCES catalog.workflow_template_version(workspace_id,id),
    CONSTRAINT uq_workflow_instance__project UNIQUE (workspace_id,project_id)
);

CREATE TABLE project.checkpoint_instance (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, sequence_no integer NOT NULL,
    type varchar(40) NOT NULL, status varchar(40) NOT NULL, creator_due_at timestamptz NOT NULL,
    client_due_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_checkpoint_instance PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_checkpoint_instance__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT uq_checkpoint_instance__sequence UNIQUE (workspace_id,project_id,sequence_no),
    CONSTRAINT ck_checkpoint_instance__sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_checkpoint_instance__status CHECK (status IN ('locked','active','submitted','changes_requested','approved','completed'))
);

ALTER TABLE project.project ADD CONSTRAINT fk_project__checkpoint__current
    FOREIGN KEY (workspace_id,current_checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE project.checkpoint_item (
    workspace_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL,
    item_type varchar(40) NOT NULL, required boolean NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_checkpoint_item PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_checkpoint_item__checkpoint__owner FOREIGN KEY (workspace_id,checkpoint_id)
        REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT ck_checkpoint_item__type CHECK (item_type IN ('FILE','EXTERNAL_LINK','TEXT')),
    CONSTRAINT ck_checkpoint_item__status CHECK (status IN ('pending','ready','submitted','accepted'))
);

CREATE TABLE project.internal_task (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, checkpoint_id uuid NOT NULL, id uuid NOT NULL,
    title varchar(240) NOT NULL, priority varchar(20) NOT NULL, due_at timestamptz NOT NULL,
    status varchar(40) NOT NULL, assignee_id uuid NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_internal_task PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_internal_task__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT fk_internal_task__checkpoint__scope FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id),
    CONSTRAINT ck_internal_task__status CHECK (status IN ('open','in_progress','blocked','done','cancelled'))
);

CREATE TABLE project.assignment (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, member_id uuid NOT NULL,
    scope_type varchar(40) NOT NULL, scope_id uuid NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_assignment PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_assignment__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT fk_assignment__membership__member FOREIGN KEY (workspace_id,member_id) REFERENCES workspace.membership(workspace_id,id),
    CONSTRAINT uq_assignment__scope UNIQUE (workspace_id,project_id,member_id,scope_type,scope_id)
);

CREATE TABLE project.dispute (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, opened_by uuid NOT NULL,
    reason text NOT NULL, prior_state varchar(40) NOT NULL, status varchar(40) NOT NULL, resolved_at timestamptz,
    CONSTRAINT pk_dispute PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_dispute__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_dispute__status CHECK (status IN ('open','resolved')),
    CONSTRAINT ck_dispute__resolution CHECK ((status='resolved')=(resolved_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_dispute__active ON project.dispute(workspace_id,project_id) WHERE status='open';

CREATE TABLE project.cancellation (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, requested_by uuid NOT NULL,
    reason text NOT NULL, status varchar(40) NOT NULL,
    CONSTRAINT pk_cancellation PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_cancellation__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_cancellation__status CHECK (status IN ('requested','confirming','cancelled','rejected'))
);
CREATE UNIQUE INDEX uq_cancellation__active ON project.cancellation(workspace_id,project_id)
    WHERE status IN ('requested','confirming');

CREATE TABLE project.cancellation_required_party (
    workspace_id uuid NOT NULL, cancellation_id uuid NOT NULL, party_id uuid NOT NULL,
    required_action varchar(40) NOT NULL, confirmed_at timestamptz,
    CONSTRAINT pk_cancellation_required_party PRIMARY KEY (workspace_id,cancellation_id,party_id),
    CONSTRAINT fk_cancellation_required_party__cancellation__owner FOREIGN KEY (workspace_id,cancellation_id)
        REFERENCES project.cancellation(workspace_id,id)
);

CREATE TABLE schedule.business_calendar (
    workspace_id uuid NOT NULL, id uuid NOT NULL, time_zone varchar(80) NOT NULL,
    workdays jsonb NOT NULL, cutoff_local_time time NOT NULL, version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_business_calendar PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_business_calendar__workspace__tenant FOREIGN KEY (workspace_id) REFERENCES workspace.workspace(id),
    CONSTRAINT ck_business_calendar__workdays_array CHECK (jsonb_typeof(workdays)='array')
);

CREATE TABLE schedule.calendar_exception (
    workspace_id uuid NOT NULL, calendar_id uuid NOT NULL, local_date date NOT NULL,
    kind varchar(40) NOT NULL, label varchar(160) NOT NULL,
    CONSTRAINT pk_calendar_exception PRIMARY KEY (workspace_id,calendar_id,local_date),
    CONSTRAINT fk_calendar_exception__calendar__owner FOREIGN KEY (workspace_id,calendar_id)
        REFERENCES schedule.business_calendar(workspace_id,id)
);

CREATE TABLE schedule.schedule_proposal (
    workspace_id uuid NOT NULL, project_id uuid NOT NULL, id uuid NOT NULL, reason text NOT NULL,
    base_version bigint NOT NULL, changes_json jsonb NOT NULL, final_delivery_changed boolean NOT NULL,
    status varchar(40) NOT NULL,
    CONSTRAINT pk_schedule_proposal PRIMARY KEY (workspace_id,id),
    CONSTRAINT fk_schedule_proposal__project__owner FOREIGN KEY (workspace_id,project_id) REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_schedule_proposal__changes_object CHECK (jsonb_typeof(changes_json)='object'),
    CONSTRAINT ck_schedule_proposal__status CHECK (status IN ('proposed','accepted','declined','expired'))
);

CREATE TABLE schedule.schedule_item (
    workspace_id uuid NOT NULL, proposal_id uuid NOT NULL, checkpoint_id uuid NOT NULL,
    old_due_at timestamptz NOT NULL, new_due_at timestamptz NOT NULL, reason_code varchar(80) NOT NULL,
    CONSTRAINT pk_schedule_item PRIMARY KEY (workspace_id,proposal_id,checkpoint_id),
    CONSTRAINT fk_schedule_item__proposal__owner FOREIGN KEY (workspace_id,proposal_id) REFERENCES schedule.schedule_proposal(workspace_id,id),
    CONSTRAINT fk_schedule_item__checkpoint__target FOREIGN KEY (workspace_id,checkpoint_id) REFERENCES project.checkpoint_instance(workspace_id,id)
);

CREATE FUNCTION agreement.reject_accepted_record_mutation() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,agreement AS $$ BEGIN RAISE EXCEPTION 'accepted agreement record is append-only' USING ERRCODE='55000'; END $$;
CREATE TRIGGER trg_party_acceptance_append_only BEFORE UPDATE OR DELETE ON agreement.party_acceptance
FOR EACH ROW EXECUTE FUNCTION agreement.reject_accepted_record_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA agreement,project,schedule FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA agreement,project,schedule TO app_runtime;
REVOKE UPDATE,DELETE ON agreement.party_acceptance FROM app_runtime;
