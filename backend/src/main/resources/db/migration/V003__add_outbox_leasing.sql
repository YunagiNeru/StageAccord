ALTER TABLE infra.outbox_event
    ADD COLUMN leased_by varchar(128),
    ADD COLUMN first_attempted_at timestamptz,
    ADD COLUMN last_error_class varchar(160);

CREATE INDEX ix_outbox_event__lease_recovery
ON infra.outbox_event (lease_until)
WHERE status = 'leased';
COMMENT ON INDEX infra.ix_outbox_event__lease_recovery IS 'worker_claim';
