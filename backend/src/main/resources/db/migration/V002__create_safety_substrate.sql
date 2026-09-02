CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS infra;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_owner') THEN
        CREATE ROLE audit_owner NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_runtime') THEN
        CREATE ROLE worker_runtime NOLOGIN NOINHERIT;
    END IF;
END
$$;

CREATE TABLE audit.audit_chain_head (
    singleton boolean NOT NULL DEFAULT true,
    terminal_sequence bigint NOT NULL DEFAULT 0,
    terminal_hash bytea NOT NULL DEFAULT decode(repeat('00', 32), 'hex'),
    CONSTRAINT pk_audit_chain_head PRIMARY KEY (singleton),
    CONSTRAINT ck_audit_chain_head__singleton CHECK (singleton),
    CONSTRAINT ck_audit_chain_head__hash_length CHECK (octet_length(terminal_hash) = 32)
);

INSERT INTO audit.audit_chain_head (singleton) VALUES (true);

CREATE TABLE audit.audit_event (
    event_id uuid NOT NULL,
    sequence bigint NOT NULL,
    previous_hash bytea NOT NULL,
    event_hash bytea NOT NULL,
    canonical_payload jsonb NOT NULL,
    actor jsonb NOT NULL,
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (event_id),
    CONSTRAINT uq_audit_event__sequence UNIQUE (sequence),
    CONSTRAINT ck_audit_event__previous_hash_length CHECK (octet_length(previous_hash) = 32),
    CONSTRAINT ck_audit_event__event_hash_length CHECK (octet_length(event_hash) = 32),
    CONSTRAINT ck_audit_event__payload_object CHECK (jsonb_typeof(canonical_payload) = 'object'),
    CONSTRAINT ck_audit_event__actor_object CHECK (jsonb_typeof(actor) = 'object')
);

CREATE INDEX ix_audit_event__audit_verify ON audit.audit_event (sequence, event_hash);
COMMENT ON INDEX audit.ix_audit_event__audit_verify IS 'audit_verify';

CREATE FUNCTION audit.reject_mutation() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, audit
AS $$
BEGIN
    RAISE EXCEPTION 'audit records are append-only' USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_audit_event_reject_mutation
BEFORE UPDATE OR DELETE ON audit.audit_event
FOR EACH ROW EXECUTE FUNCTION audit.reject_mutation();

CREATE TRIGGER trg_audit_event_reject_truncate
BEFORE TRUNCATE ON audit.audit_event
FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_mutation();

CREATE FUNCTION audit.append_event(
    requested_payload jsonb,
    requested_actor jsonb,
    requested_correlation_id uuid
) RETURNS TABLE (event_id uuid, sequence bigint, event_hash bytea)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, audit
AS $$
DECLARE
    current_head audit.audit_chain_head%ROWTYPE;
    new_event_id uuid := gen_random_uuid();
    observed_at timestamptz := clock_timestamp();
    normalized_envelope jsonb;
    new_hash bytea;
BEGIN
    IF jsonb_typeof(requested_payload) <> 'object'
            OR jsonb_typeof(requested_actor) <> 'object'
            OR requested_correlation_id IS NULL THEN
        RAISE EXCEPTION 'invalid audit event' USING ERRCODE = '22023';
    END IF;
    normalized_envelope := jsonb_build_object(
        'eventId', new_event_id,
        'payload', requested_payload,
        'actor', requested_actor,
        'correlationId', requested_correlation_id,
        'occurredAt', observed_at
    );
    IF octet_length(convert_to(normalized_envelope::text, 'UTF8')) > 65536 THEN
        RAISE EXCEPTION 'audit event exceeds 64 KiB' USING ERRCODE = '22001';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('audit.audit_event'));
    SELECT * INTO STRICT current_head FROM audit.audit_chain_head WHERE singleton FOR UPDATE;
    new_hash := public.digest(
        current_head.terminal_hash || convert_to(normalized_envelope::text, 'UTF8'),
        'sha256'
    );

    INSERT INTO audit.audit_event (
        event_id, sequence, previous_hash, event_hash, canonical_payload, actor, correlation_id, occurred_at
    ) VALUES (
        new_event_id,
        current_head.terminal_sequence + 1,
        current_head.terminal_hash,
        new_hash,
        requested_payload,
        requested_actor,
        requested_correlation_id,
        observed_at
    );
    UPDATE audit.audit_chain_head
    SET terminal_sequence = current_head.terminal_sequence + 1, terminal_hash = new_hash
    WHERE singleton;

    RETURN QUERY SELECT new_event_id, current_head.terminal_sequence + 1, new_hash;
END
$$;

ALTER TABLE audit.audit_chain_head OWNER TO audit_owner;
ALTER TABLE audit.audit_event OWNER TO audit_owner;
ALTER FUNCTION audit.reject_mutation() OWNER TO audit_owner;
ALTER FUNCTION audit.append_event(jsonb, jsonb, uuid) OWNER TO audit_owner;
REVOKE ALL ON audit.audit_chain_head, audit.audit_event FROM PUBLIC, app_runtime, worker_runtime;
REVOKE ALL ON FUNCTION audit.append_event(jsonb, jsonb, uuid) FROM PUBLIC;
GRANT USAGE ON SCHEMA audit TO audit_owner, app_runtime, worker_runtime;
GRANT EXECUTE ON FUNCTION audit.append_event(jsonb, jsonb, uuid) TO app_runtime, worker_runtime;

CREATE TABLE infra.outbox_event (
    event_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    producer varchar(64) NOT NULL,
    aggregate_type varchar(128) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_sequence bigint NOT NULL,
    event_type varchar(160) NOT NULL,
    payload jsonb NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    actor jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    attempt_count smallint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    status varchar(24) NOT NULL DEFAULT 'pending',
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT uq_outbox_event__aggregate_sequence UNIQUE (producer, aggregate_type, aggregate_id, aggregate_sequence),
    CONSTRAINT ck_outbox_event__sequence_positive CHECK (aggregate_sequence > 0),
    CONSTRAINT ck_outbox_event__attempt_range CHECK (attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_outbox_event__status CHECK (status IN ('pending', 'leased', 'delivered', 'dead_letter', 'skipped')),
    CONSTRAINT ck_outbox_event__payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_event__payload_size CHECK (octet_length(convert_to(payload::text, 'UTF8')) <= 65536),
    CONSTRAINT ck_outbox_event__actor_object CHECK (jsonb_typeof(actor) = 'object')
);

CREATE INDEX ix_outbox_event__worker_claim
ON infra.outbox_event (available_at, lease_until, occurred_at)
WHERE status = 'pending';
COMMENT ON INDEX infra.ix_outbox_event__worker_claim IS 'worker_claim';

CREATE TABLE infra.idempotency_record (
    scope_hash bytea NOT NULL,
    key_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    status_code smallint NOT NULL,
    response_ciphertext bytea NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope_hash, key_hash),
    CONSTRAINT ck_idempotency_record__scope_hash_length CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT ck_idempotency_record__key_hash_length CHECK (octet_length(key_hash) = 32),
    CONSTRAINT ck_idempotency_record__request_hash_length CHECK (octet_length(request_hash) = 32),
    CONSTRAINT ck_idempotency_record__status CHECK (status_code BETWEEN 200 AND 599),
    CONSTRAINT ck_idempotency_record__expiry CHECK (expires_at >= created_at + interval '24 hours')
);

CREATE INDEX ix_idempotency_record__expiry_reaper ON infra.idempotency_record (expires_at);
COMMENT ON INDEX infra.ix_idempotency_record__expiry_reaper IS 'expiry_reaper';

CREATE TABLE infra.consumer_dedup (
    consumer_name varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL,
    result_digest bytea NOT NULL,
    CONSTRAINT pk_consumer_dedup PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_consumer_dedup__result_digest_length CHECK (octet_length(result_digest) = 32)
);

CREATE TABLE infra.outbox_dead_letter (
    event_id uuid NOT NULL,
    owner_module varchar(64) NOT NULL,
    error_class varchar(160) NOT NULL,
    redacted_message varchar(1024) NOT NULL,
    attempt_count smallint NOT NULL,
    first_failed_at timestamptz NOT NULL,
    last_failed_at timestamptz NOT NULL,
    isolated_at timestamptz NOT NULL,
    CONSTRAINT pk_outbox_dead_letter PRIMARY KEY (event_id),
    CONSTRAINT fk_outbox_dead_letter__outbox_event__event FOREIGN KEY (event_id)
        REFERENCES infra.outbox_event (event_id),
    CONSTRAINT ck_outbox_dead_letter__attempt_range CHECK (attempt_count BETWEEN 1 AND 10),
    CONSTRAINT ck_outbox_dead_letter__failure_order CHECK (last_failed_at >= first_failed_at)
);

REVOKE ALL ON ALL TABLES IN SCHEMA infra FROM PUBLIC;
GRANT USAGE ON SCHEMA infra TO app_runtime, worker_runtime;
GRANT SELECT, INSERT ON infra.outbox_event, infra.idempotency_record TO app_runtime;
GRANT SELECT, UPDATE ON infra.outbox_event TO worker_runtime;
GRANT SELECT, INSERT ON infra.consumer_dedup, infra.outbox_dead_letter TO worker_runtime;
