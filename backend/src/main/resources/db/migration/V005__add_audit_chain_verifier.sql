DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_verifier') THEN
        CREATE ROLE audit_verifier NOLOGIN NOINHERIT;
    END IF;
END
$$;

CREATE FUNCTION audit.verify_chain()
RETURNS TABLE (
    valid boolean,
    checked_events bigint,
    failed_sequence bigint,
    terminal_sequence bigint,
    terminal_hash bytea
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, audit
AS $$
DECLARE
    item audit.audit_event%ROWTYPE;
    expected_sequence bigint := 1;
    expected_previous_hash bytea := decode(repeat('00', 32), 'hex');
    calculated_hash bytea;
    head audit.audit_chain_head%ROWTYPE;
BEGIN
    FOR item IN SELECT * FROM audit.audit_event ORDER BY sequence LOOP
        calculated_hash := public.digest(
            expected_previous_hash || convert_to(jsonb_build_object(
                'eventId', item.event_id,
                'payload', item.canonical_payload,
                'actor', item.actor,
                'correlationId', item.correlation_id,
                'occurredAt', item.occurred_at
            )::text, 'UTF8'),
            'sha256'
        );
        IF item.sequence <> expected_sequence
                OR item.previous_hash <> expected_previous_hash
                OR item.event_hash <> calculated_hash THEN
            RETURN QUERY SELECT false, expected_sequence - 1, item.sequence, NULL::bigint, NULL::bytea;
            RETURN;
        END IF;
        expected_previous_hash := item.event_hash;
        expected_sequence := expected_sequence + 1;
    END LOOP;

    SELECT * INTO STRICT head FROM audit.audit_chain_head WHERE singleton;
    IF head.terminal_sequence <> expected_sequence - 1 OR head.terminal_hash <> expected_previous_hash THEN
        RETURN QUERY SELECT false, expected_sequence - 1, head.terminal_sequence,
                head.terminal_sequence, head.terminal_hash;
        RETURN;
    END IF;
    RETURN QUERY SELECT true, expected_sequence - 1, NULL::bigint,
            head.terminal_sequence, head.terminal_hash;
END
$$;

ALTER FUNCTION audit.verify_chain() OWNER TO audit_owner;
REVOKE ALL ON FUNCTION audit.verify_chain() FROM PUBLIC;
GRANT USAGE ON SCHEMA audit TO audit_verifier;
GRANT EXECUTE ON FUNCTION audit.verify_chain() TO audit_verifier;
