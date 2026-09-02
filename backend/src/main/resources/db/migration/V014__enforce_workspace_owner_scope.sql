CREATE UNIQUE INDEX uq_workspace__active_owner_account
ON workspace.workspace (owner_account_id)
WHERE status <> 'closed';

CREATE UNIQUE INDEX uq_invitation__active_email
ON workspace.invitation (workspace_id, email_digest_v2)
WHERE consumed_at IS NULL AND revoked_at IS NULL;
