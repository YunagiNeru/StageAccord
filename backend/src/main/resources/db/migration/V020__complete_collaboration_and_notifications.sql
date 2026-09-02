ALTER TABLE collab.progress_update ADD CONSTRAINT uq_progress_update__id UNIQUE (id);
ALTER TABLE collab.comment ADD CONSTRAINT uq_comment__id UNIQUE (id);
ALTER TABLE collab.revision_draft ADD CONSTRAINT uq_revision_draft__id UNIQUE (id);
ALTER TABLE collab.revision_round ADD CONSTRAINT uq_revision_round__id UNIQUE (id);
ALTER TABLE collab.approval_process ADD CONSTRAINT uq_approval_process__id UNIQUE (id);
ALTER TABLE collab.delivery_package ADD CONSTRAINT uq_delivery_package__id UNIQUE (id);
ALTER TABLE schedule.notification_request
    ADD COLUMN read_at timestamptz,
    ADD CONSTRAINT uq_notification_request__id UNIQUE (id);

CREATE TABLE collab.project_export (
    workspace_id uuid NOT NULL,
    project_id uuid NOT NULL,
    id uuid NOT NULL,
    requested_by uuid NOT NULL,
    audience varchar(20) NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_project_export PRIMARY KEY (workspace_id,id),
    CONSTRAINT uq_project_export__id UNIQUE (id),
    CONSTRAINT fk_project_export__project__scope FOREIGN KEY (workspace_id,project_id)
        REFERENCES project.project(workspace_id,id),
    CONSTRAINT ck_project_export__audience CHECK (audience IN ('creator','client')),
    CONSTRAINT ck_project_export__payload CHECK (jsonb_typeof(payload_json)='object')
);

REVOKE ALL ON collab.project_export FROM PUBLIC;
GRANT SELECT,INSERT ON collab.project_export TO app_runtime;
GRANT SELECT,UPDATE ON schedule.notification_request TO app_runtime;
