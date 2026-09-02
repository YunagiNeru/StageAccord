package com.stageaccord.identityaccess.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.stageaccord.identityaccess.application.AccountAuthentication;
import com.stageaccord.identityaccess.application.AuthChallenge;
import com.stageaccord.identityaccess.application.AuthFactorDescriptor;
import com.stageaccord.identityaccess.application.ClientAccessGrant;
import com.stageaccord.identityaccess.application.ClientSessionDescriptor;
import com.stageaccord.identityaccess.application.IdentityStore;
import com.stageaccord.identityaccess.application.ProtectedValue;
import com.stageaccord.identityaccess.application.SessionDescriptor;
import com.stageaccord.identityaccess.application.RecoveryCase;
import com.stageaccord.identityaccess.domain.AuthStrength;

@Repository
public class JdbcIdentityStore implements IdentityStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcIdentityStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void createChallenge(AuthChallenge challenge) {
        requireSingle(jdbc.update("""
                INSERT INTO iam.auth_challenge (
                    id, account_id, purpose, challenge_digest, digest_key_id, subject_digest,
                    protected_context, expires_at, consumed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, challenge.id(), challenge.accountId(), challenge.purpose(), challenge.tokenDigest(),
                challenge.digestKeyId(), challenge.subjectDigest(), write(challenge.protectedSubject()),
                Timestamp.from(challenge.expiresAt()), timestamp(challenge.consumedAt())));
    }

    @Override
    public Optional<AuthChallenge> lockChallenge(UUID id) {
        return jdbc.query("""
                SELECT id, account_id, purpose, challenge_digest, digest_key_id, subject_digest,
                       protected_context::text, expires_at, consumed_at
                FROM iam.auth_challenge WHERE id = ? FOR UPDATE
                """, this::mapChallenge, id).stream().findFirst();
    }

    @Override
    public void consumeChallenge(UUID id, Instant consumedAt) {
        requireSingle(jdbc.update("""
                UPDATE iam.auth_challenge SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(consumedAt), id, Timestamp.from(consumedAt)));
    }

    @Override
    public void attachChallengeToAccount(UUID id, UUID accountId) {
        requireSingle(jdbc.update("""
                UPDATE iam.auth_challenge SET account_id = ?
                WHERE id = ? AND account_id IS NULL AND consumed_at IS NOT NULL
                """, accountId, id));
    }

    @Override
    public void createAccount(UUID accountId, byte[] emailDigest, ProtectedValue protectedEmail,
            String encodedPassword, Instant now) {
        requireSingle(jdbc.update("""
                INSERT INTO iam.account (
                    id, email_digest_v2, email_ciphertext, status, auth_generation, created_at
                ) VALUES (?, ?, ?::jsonb, 'pending', 0, ?)
                """, accountId, emailDigest, write(protectedEmail), Timestamp.from(now)));
        if (encodedPassword != null) {
            requireSingle(jdbc.update("""
                    INSERT INTO iam.credential (
                        account_id, credential_id, type, credential_material, sign_count, status
                    ) VALUES (?, ?, 'password', ?::jsonb, 0, 'active')
                    """, accountId, UUID.randomUUID(), write(new PasswordMaterial(encodedPassword))));
        }
    }

    @Override
    public void createPendingTotp(UUID accountId, UUID credentialId, ProtectedValue protectedSecret) {
        requireSingle(jdbc.update("""
                INSERT INTO iam.credential (
                    account_id, credential_id, type, credential_material, sign_count, status
                ) VALUES (?, ?, 'totp', ?::jsonb, 0, 'pending')
                """, accountId, credentialId, write(protectedSecret)));
    }

    @Override
    public ProtectedValue requirePendingTotp(UUID accountId, UUID credentialId) {
        return jdbc.query("""
                SELECT credential_material::text FROM iam.credential
                WHERE account_id = ? AND credential_id = ? AND type = 'totp' AND status = 'pending'
                FOR UPDATE
                """, (result, row) -> read(result.getString(1), ProtectedValue.class), accountId, credentialId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("pending TOTP not found"));
    }

    @Override
    public void activateTotpAndAccount(UUID accountId, UUID credentialId) {
        requireSingle(jdbc.update("""
                UPDATE iam.credential SET status = 'active'
                WHERE account_id = ? AND credential_id = ? AND type = 'totp' AND status = 'pending'
                """, accountId, credentialId));
        requireSingle(jdbc.update("UPDATE iam.account SET status = 'active', version = version + 1 "
                + "WHERE id = ? AND status = 'pending'", accountId));
    }

    @Override
    public void saveRecoveryCodes(UUID accountId, int generation, String digestKeyId,
            List<byte[]> digests, Instant expiresAt) {
        jdbc.batchUpdate("""
                INSERT INTO iam.recovery_code (
                    account_id, generation, digest_key_id, code_digest, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """, digests, digests.size(), (statement, digest) -> {
                    statement.setObject(1, accountId);
                    statement.setInt(2, generation);
                    statement.setString(3, digestKeyId);
                    statement.setBytes(4, digest);
                    statement.setTimestamp(5, Timestamp.from(expiresAt));
                });
    }

    @Override
    public Optional<AccountAuthentication> findAuthentication(byte[] emailDigest) {
        return findAccount("a.email_digest_v2 = ?", emailDigest);
    }

    @Override
    public Optional<AccountAuthentication> findAuthenticationByAccountId(UUID accountId) {
        return findAccount("a.id = ?", accountId);
    }

    private Optional<AccountAuthentication> findAccount(String predicate, Object argument) {
        return jdbc.query("""
                SELECT a.id, a.email_digest_v2, a.status, a.auth_generation,
                       password.credential_material::text AS password_material,
                       totp.credential_material::text AS totp_material
                FROM iam.account a
                LEFT JOIN iam.credential password
                  ON password.account_id = a.id AND password.type = 'password' AND password.status = 'active'
                LEFT JOIN iam.credential totp
                  ON totp.account_id = a.id AND totp.type = 'totp' AND totp.status = 'active'
                WHERE %s
                """.formatted(predicate), this::mapAuthentication, argument).stream().findFirst();
    }

    @Override
    public void createSession(SessionDescriptor session) {
        requireSingle(jdbc.update("""
                INSERT INTO iam.session_record (
                    id, account_id, token_digest, digest_key_id, auth_strength, auth_generation,
                    authenticated_at, last_seen_at, absolute_expires_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.id(), session.accountId(), session.tokenDigest(), session.digestKeyId(),
                databaseStrength(session.strength()), session.authGeneration(),
                Timestamp.from(session.authenticatedAt()), Timestamp.from(session.lastSeenAt()),
                Timestamp.from(session.absoluteExpiresAt()), timestamp(session.revokedAt())));
    }

    @Override
    public Optional<SessionDescriptor> findSession(byte[] tokenDigest, String digestKeyId) {
        return jdbc.query("""
                SELECT id, account_id, token_digest, digest_key_id, auth_strength, auth_generation,
                       authenticated_at, last_seen_at, absolute_expires_at, revoked_at
                FROM iam.session_record WHERE token_digest = ? AND digest_key_id = ?
                """, this::mapSession, tokenDigest, digestKeyId).stream().findFirst();
    }

    @Override
    public void touchSession(UUID sessionId, Instant lastSeenAt) {
        requireSingle(jdbc.update("UPDATE iam.session_record SET last_seen_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(lastSeenAt), sessionId));
    }

    @Override
    public List<SessionDescriptor> listSessions(UUID accountId) {
        return jdbc.query("""
                SELECT id, account_id, token_digest, digest_key_id, auth_strength, auth_generation,
                       authenticated_at, last_seen_at, absolute_expires_at, revoked_at
                FROM iam.session_record WHERE account_id = ? ORDER BY authenticated_at DESC
                """, this::mapSession, accountId);
    }

    @Override
    public void revokeSession(UUID accountId, UUID sessionId, Instant revokedAt) {
        int updated = jdbc.update("""
                UPDATE iam.session_record SET revoked_at = ?
                WHERE account_id = ? AND id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), accountId, sessionId);
        if (updated > 1) throw new IllegalStateException("multiple sessions updated");
    }

    @Override
    public void revokeAllSessions(UUID accountId, Instant revokedAt) {
        jdbc.update("UPDATE iam.session_record SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL",
                Timestamp.from(revokedAt), accountId);
    }

    @Override
    public void refreshSession(UUID sessionId, Instant authenticatedAt) {
        requireSingle(jdbc.update("UPDATE iam.session_record SET authenticated_at=?,last_seen_at=? "
                + "WHERE id=? AND revoked_at IS NULL", Timestamp.from(authenticatedAt),
                Timestamp.from(authenticatedAt), sessionId));
    }

    @Override
    public List<AuthFactorDescriptor> listFactors(UUID accountId) {
        return jdbc.query("SELECT credential_id,type,status FROM iam.credential WHERE account_id=? ORDER BY type,credential_id",
                (r, n) -> new AuthFactorDescriptor(r.getObject(1, UUID.class), r.getString(2), r.getString(3)),
                accountId);
    }

    @Override
    public int countActiveCredentials(UUID accountId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM iam.credential WHERE account_id=? AND type=? AND status='active'",
                Integer.class, accountId, type);
    }

    @Override
    public void revokeCredentials(UUID accountId, String type, UUID credentialId) {
        String credentialClause = credentialId == null ? "" : " AND credential_id=?";
        Object[] arguments = credentialId == null
                ? new Object[] { accountId, type }
                : new Object[] { accountId, type, credentialId };
        int updated = jdbc.update("UPDATE iam.credential SET status='revoked' "
                + "WHERE account_id=? AND type=? AND status='active'" + credentialClause, arguments);
        if (updated < 1) throw new IllegalStateException("active credential not found");
    }

    @Override
    public int replacePasswordAndAdvanceGeneration(UUID accountId, String encodedPassword) {
        requireSingle(jdbc.update("UPDATE iam.credential SET credential_material=?::jsonb "
                + "WHERE account_id=? AND type='password' AND status='active'",
                write(new PasswordMaterial(encodedPassword)), accountId));
        requireSingle(jdbc.update("UPDATE iam.account SET auth_generation=auth_generation+1,version=version+1 WHERE id=?",
                accountId));
        return jdbc.queryForObject("SELECT auth_generation FROM iam.account WHERE id=?", Integer.class, accountId);
    }

    @Override
    public int advanceAuthGeneration(UUID accountId) {
        requireSingle(jdbc.update("UPDATE iam.account SET auth_generation=auth_generation+1,version=version+1 WHERE id=?",
                accountId));
        return jdbc.queryForObject("SELECT auth_generation FROM iam.account WHERE id=?", Integer.class, accountId);
    }

    @Override
    public void invalidateRecoveryCodes(UUID accountId, Instant usedAt) {
        jdbc.update("UPDATE iam.recovery_code SET used_at=? WHERE account_id=? AND used_at IS NULL",
                Timestamp.from(usedAt), accountId);
    }

    @Override
    public UUID createRecoveryCase(UUID accountId, String method, Instant requestedAt, Instant notBefore) {
        UUID id = UUID.randomUUID();
        requireSingle(jdbc.update("INSERT INTO iam.recovery_case"
                + "(id,account_id,method,status,requested_at,not_before,requested_by) "
                + "VALUES (?,?,?,'pending',?,?,?)", id, accountId, method,
                Timestamp.from(requestedAt), Timestamp.from(notBefore), accountId));
        return id;
    }

    @Override
    public Optional<RecoveryCase> lockRecoveryCase(UUID id) {
        return jdbc.query("SELECT id,account_id,method,status,not_before,completed_at "
                + "FROM iam.recovery_case WHERE id=? FOR UPDATE",
                (r, n) -> new RecoveryCase(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getString(3), r.getString(4), r.getTimestamp(5).toInstant(),
                        instant(r.getTimestamp(6))), id).stream().findFirst();
    }

    @Override
    public boolean consumeRecoveryCode(UUID accountId, int generation, byte[] digest, Instant usedAt) {
        return jdbc.update("UPDATE iam.recovery_code SET used_at=? WHERE account_id=? AND generation=? "
                + "AND digest_key_id='identity-v1' AND code_digest=? AND used_at IS NULL AND expires_at>?",
                Timestamp.from(usedAt), accountId, generation, digest, Timestamp.from(usedAt)) == 1;
    }

    @Override
    public void completeRecoveryCase(UUID id, Instant completedAt) {
        requireSingle(jdbc.update("UPDATE iam.recovery_case SET status='completed',completed_at=? "
                + "WHERE id=? AND status='pending'", Timestamp.from(completedAt), id));
    }

    @Override
    public Optional<ClientAccessGrant> lockClientAccessGrant(byte[] tokenDigest, String digestKeyId) {
        return jdbc.query("""
                SELECT workspace_id,id,project_id,email_digest_v2,client_role,auth_generation,
                       expires_at,consumed_at,revoked_at
                FROM iam.client_access_grant WHERE token_digest=? AND digest_key_id=? FOR UPDATE
                """, (r, n) -> new ClientAccessGrant(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class), r.getBytes(4), r.getString(5), r.getInt(6),
                        r.getTimestamp(7).toInstant(), instant(r.getTimestamp(8)), instant(r.getTimestamp(9))),
                tokenDigest, digestKeyId).stream().findFirst();
    }

    @Override
    public void consumeClientAccessGrant(UUID workspaceId, UUID id, Instant consumedAt) {
        requireSingle(jdbc.update("UPDATE iam.client_access_grant SET consumed_at=? "
                + "WHERE workspace_id=? AND id=? AND consumed_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(consumedAt), workspaceId, id));
    }

    @Override
    public void createClientSession(ClientAccessGrant grant, SessionDescriptor session) {
        requireSingle(jdbc.update("""
                INSERT INTO iam.client_session(workspace_id,id,project_id,token_digest,digest_key_id,
                    email_digest_v2,client_role,auth_generation,authenticated_at,last_seen_at,
                    absolute_expires_at,revoked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, grant.workspaceId(), session.id(), grant.projectId(), session.tokenDigest(),
                session.digestKeyId(), grant.emailDigest(), grant.role(), grant.authGeneration(),
                Timestamp.from(session.authenticatedAt()), Timestamp.from(session.lastSeenAt()),
                Timestamp.from(session.absoluteExpiresAt()), timestamp(session.revokedAt())));
    }

    @Override
    public void createPasskey(UUID accountId, UUID credentialId, String webAuthnCredentialId) {
        requireSingle(jdbc.update("INSERT INTO iam.credential"
                + "(account_id,credential_id,type,credential_material,sign_count,status) "
                + "VALUES (?,?,'passkey',?::jsonb,0,'active')", accountId, credentialId,
                write(new PasskeyMaterial(webAuthnCredentialId))));
    }

    @Override
    public Optional<String> findPasskeyExternalId(UUID accountId, UUID credentialId) {
        return jdbc.query("SELECT credential_material::text FROM iam.credential "
                + "WHERE account_id=? AND credential_id=? AND type='passkey' AND status='active'",
                (r, n) -> read(r.getString(1), PasskeyMaterial.class).webAuthnCredentialId(),
                accountId, credentialId).stream().findFirst();
    }

    @Override
    public void activateAccount(UUID accountId) {
        int updated = jdbc.update("UPDATE iam.account SET status='active',version=version+1 "
                + "WHERE id=? AND status='pending'", accountId);
        if (updated > 1) throw new IllegalStateException("multiple accounts updated");
    }

    @Override
    public Optional<ClientSessionDescriptor> findClientSession(byte[] tokenDigest, String digestKeyId) {
        return jdbc.query("""
                SELECT workspace_id,id,project_id,client_role,auth_generation,authenticated_at,
                       last_seen_at,absolute_expires_at,revoked_at
                FROM iam.client_session WHERE token_digest=? AND digest_key_id=?
                """, (r, n) -> new ClientSessionDescriptor(r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getString(4), r.getInt(5),
                        r.getTimestamp(6).toInstant(), r.getTimestamp(7).toInstant(),
                        r.getTimestamp(8).toInstant(), instant(r.getTimestamp(9))),
                tokenDigest, digestKeyId).stream().findFirst();
    }

    @Override
    public void touchClientSession(UUID workspaceId, UUID sessionId, Instant lastSeenAt) {
        requireSingle(jdbc.update("UPDATE iam.client_session SET last_seen_at=? "
                + "WHERE workspace_id=? AND id=? AND revoked_at IS NULL",
                Timestamp.from(lastSeenAt), workspaceId, sessionId));
    }

    private AuthChallenge mapChallenge(ResultSet result, int row) throws SQLException {
        return new AuthChallenge(result.getObject("id", UUID.class), result.getObject("account_id", UUID.class),
                result.getString("purpose"), result.getBytes("challenge_digest"),
                result.getString("digest_key_id"), result.getBytes("subject_digest"),
                read(result.getString("protected_context"), ProtectedValue.class),
                result.getTimestamp("expires_at").toInstant(), instant(result.getTimestamp("consumed_at")));
    }

    private AccountAuthentication mapAuthentication(ResultSet result, int row) throws SQLException {
        String passwordJson = result.getString("password_material");
        String totpJson = result.getString("totp_material");
        return new AccountAuthentication(result.getObject("id", UUID.class), result.getBytes("email_digest_v2"),
                result.getString("status"),
                result.getInt("auth_generation"),
                passwordJson == null ? null : read(passwordJson, PasswordMaterial.class).encodedPassword(),
                totpJson == null ? null : read(totpJson, ProtectedValue.class));
    }

    private SessionDescriptor mapSession(ResultSet result, int row) throws SQLException {
        return new SessionDescriptor(result.getObject("id", UUID.class),
                result.getObject("account_id", UUID.class), result.getBytes("token_digest"),
                result.getString("digest_key_id"),
                AuthStrength.valueOf(result.getString("auth_strength").toUpperCase(java.util.Locale.ROOT)),
                result.getInt("auth_generation"), result.getTimestamp("authenticated_at").toInstant(),
                result.getTimestamp("last_seen_at").toInstant(),
                result.getTimestamp("absolute_expires_at").toInstant(),
                instant(result.getTimestamp("revoked_at")));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("identity value serialization failed", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("identity value verification failed", exception);
        }
    }

    private static String databaseStrength(AuthStrength strength) {
        return strength.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireSingle(int updated) {
        if (updated != 1) throw new IllegalStateException("identity state changed concurrently");
    }

    private record PasswordMaterial(String encodedPassword) {}
    private record PasskeyMaterial(String webAuthnCredentialId) {}
}
