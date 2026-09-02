ALTER TABLE infra.idempotency_record
    ADD COLUMN state varchar(16);

UPDATE infra.idempotency_record SET state = 'completed';

ALTER TABLE infra.idempotency_record
    ALTER COLUMN state SET NOT NULL,
    ALTER COLUMN status_code DROP NOT NULL,
    ALTER COLUMN response_ciphertext DROP NOT NULL,
    ADD CONSTRAINT ck_idempotency_record__state
        CHECK (state IN ('pending', 'completed')),
    ADD CONSTRAINT ck_idempotency_record__completion
        CHECK ((state = 'pending' AND status_code IS NULL AND response_ciphertext IS NULL)
            OR (state = 'completed' AND status_code IS NOT NULL AND response_ciphertext IS NOT NULL));

GRANT UPDATE, DELETE ON infra.idempotency_record TO app_runtime;
