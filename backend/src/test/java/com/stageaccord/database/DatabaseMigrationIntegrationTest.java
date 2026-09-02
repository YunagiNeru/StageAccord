package com.stageaccord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.stageaccord.sharedkernel.infrastructure.outbox.JdbcOutboxStore;
import com.stageaccord.sharedkernel.application.CommandRejectedException;
import com.stageaccord.sharedkernel.application.RejectionCode;
import com.stageaccord.sharedkernel.idempotency.IdempotencyFingerprint;
import com.stageaccord.sharedkernel.idempotency.IdempotencyReservation;
import com.stageaccord.sharedkernel.infrastructure.idempotency.JdbcIdempotencyStore;
import com.stageaccord.auditadmin.infrastructure.AuditChainVerifier;

@EnabledIfEnvironmentVariable(named = "STAGE_ACCORD_TEST_DB_URL", matches = ".+")
class DatabaseMigrationIntegrationTest {

    private static final Set<String> SCHEMAS = Set.of(
            "iam", "workspace", "catalog", "intake", "agreement", "project",
            "collab", "file_store", "privacy", "schedule", "billing", "audit", "infra");

    @Test
    void migrationCreatesEveryOwnedSchemaInDedicatedDummyDatabase() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");

        Flyway flyway = migrateFresh(url, username, password);
        flyway.validate();

        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.prepareStatement(
                        "select schema_name from information_schema.schemata where schema_name = any (?)")) {
            var names = connection.createArrayOf("text", SCHEMAS.toArray());
            statement.setArray(1, names);
            try (var result = statement.executeQuery()) {
                var actual = new java.util.HashSet<String>();
                while (result.next()) actual.add(result.getString(1));
                assertThat(actual).isEqualTo(SCHEMAS);
            }
        }
    }

    @Test
    void safetySubstrateRejectsAuditMutationAndDuplicateAggregateSequence() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");

        migrateFresh(url, username, password);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            UUIDs ids = new UUIDs();
            try (var append = connection.prepareStatement(
                    "select event_id, sequence, event_hash from audit.append_event(?::jsonb, ?::jsonb, ?::uuid)")) {
                append.setString(1, "{\"action\":\"test\",\"result\":\"allowed\"}");
                append.setString(2, "{\"principalId\":\"" + ids.actor + "\"}");
                append.setString(3, ids.correlation.toString());
                try (var result = append.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong("sequence")).isPositive();
                    assertThat(result.getBytes("event_hash")).hasSize(32);
                    ids.auditEvent = result.getObject("event_id", java.util.UUID.class);
                }
            }
            connection.commit();

            java.util.UUID auditEvent = ids.auditEvent;
            assertThatThrownBy(() -> execute(connection,
                    "update audit.audit_event set correlation_id = gen_random_uuid() where event_id = '" + auditEvent + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
            connection.rollback();

            String insert = "insert into infra.outbox_event "
                    + "(event_id, workspace_id, producer, aggregate_type, aggregate_id, aggregate_sequence, "
                    + "event_type, payload, correlation_id, actor, occurred_at, available_at) values "
                    + "(gen_random_uuid(), '" + ids.workspace + "', 'test', 'Aggregate', '" + ids.aggregate
                    + "', 1, 'test.created.v1', '{}'::jsonb, '" + ids.correlation
                    + "', '{}'::jsonb, now(), now())";
            execute(connection, insert);
            connection.commit();
            assertThatThrownBy(() -> execute(connection, insert))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_outbox_event__aggregate_sequence");
            connection.rollback();
        }
    }

    @Test
    void applicationRoleCanAppendAuditButCannotReadOrMutateAuditTables() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            execute(connection, "set local role app_runtime");
            try (var append = connection.prepareStatement(
                    "select sequence from audit.append_event(?::jsonb, ?::jsonb, ?::uuid)")) {
                append.setString(1, "{\"action\":\"role-test\"}");
                append.setString(2, "{\"principalId\":\"" + java.util.UUID.randomUUID() + "\"}");
                append.setString(3, java.util.UUID.randomUUID().toString());
                try (var result = append.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(1L);
                }
            }
            connection.commit();

            execute(connection, "set local role app_runtime");
            assertThatThrownBy(() -> execute(connection, "select * from audit.audit_event"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audit_event");
            connection.rollback();
        }
    }

    @Test
    void outboxLeasePreservesAggregateOrderAndRecoversAfterWorkerLoss() {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        var dataSource = new DriverManagerDataSource(url, username, password);
        var jdbc = new JdbcTemplate(dataSource);
        var store = new JdbcOutboxStore(jdbc);
        Instant now = Instant.parse("2026-09-02T08:00:00Z");
        UUID workspaceId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();

        insertOutbox(jdbc, firstEventId, workspaceId, aggregateId, correlationId, 1, now.minusSeconds(2));
        insertOutbox(jdbc, secondEventId, workspaceId, aggregateId, correlationId, 2, now.minusSeconds(1));

        var firstLease = store.claimNext("worker-a", now, Duration.ofSeconds(60)).orElseThrow();
        assertThat(firstLease.eventId()).isEqualTo(firstEventId);
        assertThat(firstLease.attemptCount()).isEqualTo(1);
        assertThat(store.claimNext("worker-b", now.plusSeconds(30), Duration.ofSeconds(60))).isEmpty();

        var recoveredLease = store.claimNext("worker-b", now.plusSeconds(61), Duration.ofSeconds(60)).orElseThrow();
        assertThat(recoveredLease.eventId()).isEqualTo(firstEventId);
        assertThat(recoveredLease.attemptCount()).isEqualTo(2);
        assertThat(recoveredLease.firstAttemptedAt()).isEqualTo(now);
        store.markDelivered(firstEventId);

        var secondLease = store.claimNext("worker-b", now.plusSeconds(61), Duration.ofSeconds(60)).orElseThrow();
        assertThat(secondLease.eventId()).isEqualTo(secondEventId);
        store.isolate(secondLease, now.plusSeconds(62), "PermanentDeliveryFailure");

        assertThat(jdbc.queryForObject(
                "select status from infra.outbox_event where event_id = ?", String.class, secondEventId))
                .isEqualTo("dead_letter");
        assertThat(jdbc.queryForObject(
                "select redacted_message from infra.outbox_dead_letter where event_id = ?",
                String.class, secondEventId))
                .isEqualTo("delivery failed");
    }

    @Test
    void idempotencyReservationRejectsChangedRequestsAndReplaysOnlyCompletedCiphertext() {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        var dataSource = new DriverManagerDataSource(url, username, password);
        var store = new JdbcIdempotencyStore(new JdbcTemplate(dataSource));
        Instant now = Instant.parse("2026-09-02T08:00:00Z");
        var fingerprint = new IdempotencyFingerprint(hashByte(1), hashByte(2), hashByte(3));
        var changedRequest = new IdempotencyFingerprint(hashByte(1), hashByte(2), hashByte(4));

        assertThat(store.reserve(fingerprint, now, now.plus(Duration.ofHours(24))))
                .isInstanceOf(IdempotencyReservation.Reserved.class);
        assertThat(store.reserve(fingerprint, now.plusSeconds(1), now.plus(Duration.ofHours(25))))
                .isInstanceOf(IdempotencyReservation.InProgress.class);
        assertThatThrownBy(() -> store.reserve(
                changedRequest, now.plusSeconds(1), now.plus(Duration.ofHours(25))))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        error -> assertThat(error.code()).isEqualTo(RejectionCode.IDEMPOTENCY_KEY_REUSED));

        byte[] ciphertext = new byte[] {9, 8, 7};
        store.complete(fingerprint, 201, ciphertext);
        var replayed = (IdempotencyReservation.Replayed) store.reserve(
                fingerprint, now.plusSeconds(2), now.plus(Duration.ofHours(25)));
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.responseCiphertext()).isEqualTo(ciphertext);
    }

    @Test
    void auditVerifierDetectsTheFirstBrokenHash() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        try (var connection = DriverManager.getConnection(url, username, password)) {
            for (int index = 0; index < 2; index++) {
                try (var append = connection.prepareStatement(
                        "select * from audit.append_event(?::jsonb, ?::jsonb, ?::uuid)")) {
                    append.setString(1, "{\"action\":\"verify-" + index + "\"}");
                    append.setString(2, "{\"principalId\":\"" + UUID.randomUUID() + "\"}");
                    append.setString(3, UUID.randomUUID().toString());
                    append.execute();
                }
            }
            assertThat(AuditChainVerifier.verify(url, username, password).valid()).isTrue();

            execute(connection, "alter table audit.audit_event disable trigger trg_audit_event_reject_mutation");
            execute(connection, "update audit.audit_event set event_hash = decode(repeat('ff', 32), 'hex') where sequence = 2");
            execute(connection, "alter table audit.audit_event enable trigger trg_audit_event_reject_mutation");
        }

        var broken = AuditChainVerifier.verify(url, username, password);
        assertThat(broken.valid()).isFalse();
        assertThat(broken.checkedEvents()).isEqualTo(1);
        assertThat(broken.failedSequence()).isEqualTo(2);
        assertThat(broken.asJson()).doesNotContain(url, username, password);
    }

    @Test
    void tenantConstraintVerifierRejectsMissingWorkspaceKeys() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        try (var connection = DriverManager.getConnection(url, username, password)) {
            execute(connection, """
                    create table workspace.tenant_parent_fixture (
                        workspace_id uuid not null,
                        id uuid not null,
                        constraint pk_tenant_parent_fixture primary key (workspace_id, id),
                        constraint uq_tenant_parent_fixture__id unique (id)
                    )
                    """);
            execute(connection, """
                    create table workspace.tenant_child_fixture (
                        workspace_id uuid not null,
                        id uuid not null,
                        parent_id uuid not null,
                        constraint pk_tenant_child_fixture primary key (id),
                        constraint fk_tenant_child_fixture__parent__unsafe foreign key (parent_id)
                            references workspace.tenant_parent_fixture (id)
                    )
                    """);

            try (var statement = connection.prepareStatement("""
                    select violation from infra.verify_tenant_constraints()
                    where table_name = 'tenant_child_fixture'
                    """); var result = statement.executeQuery()) {
                var violations = new java.util.HashSet<String>();
                while (result.next()) violations.add(result.getString(1));
                assertThat(violations).containsExactlyInAnyOrder(
                        "PRIMARY_KEY_MISSING_WORKSPACE",
                        "FOREIGN_KEY_MISSING_WORKSPACE",
                        "TENANT_FOREIGN_KEY_NOT_COMPOSITE");
            }
        }
    }

    @Test
    void identitySchemaRejectsWorkspaceWithoutActiveOwnerAndHasNoTenantViolations() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        try (var connection = DriverManager.getConnection(url, username, password)) {
            UUID accountId = UUID.randomUUID();
            UUID workspaceId = UUID.randomUUID();
            execute(connection, "insert into iam.account (id, email_digest_v2, email_ciphertext, status, auth_generation) values ('"
                    + accountId + "', decode(repeat('11', 32), 'hex'), '{}', 'active', 0)");
            connection.setAutoCommit(false);
            execute(connection, "insert into workspace.workspace (id, owner_account_id, name, status, billing_mode) values ('"
                    + workspaceId + "', '" + accountId + "', 'Dummy workspace', 'active', 'trial')");
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("active owner membership");
            connection.rollback();

            execute(connection, "insert into workspace.workspace (id, owner_account_id, name, status, billing_mode) values ('"
                    + workspaceId + "', '" + accountId + "', 'Dummy workspace', 'active', 'trial')");
            execute(connection, "insert into workspace.membership (workspace_id, id, account_id, role, status, joined_at) values ('"
                    + workspaceId + "', '" + UUID.randomUUID() + "', '" + accountId + "', 'owner', 'active', now())");
            connection.commit();

            try (var statement = connection.prepareStatement("select * from infra.verify_tenant_constraints()");
                    var result = statement.executeQuery()) {
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void catalogRejectsCrossWorkspaceReferencesAndPublishedSnapshotMutation() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");
        migrateFresh(url, username, password);

        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            UUID firstWorkspace = seedWorkspace(connection, "21");
            UUID secondWorkspace = seedWorkspace(connection, "22");
            UUID crossProfileId = UUID.randomUUID();
            execute(connection, "insert into catalog.creator_profile (workspace_id,id,slug,draft_json,intake_status) values ('"
                    + firstWorkspace + "','" + crossProfileId + "','dummy-profile','{}','open')");
            assertThatThrownBy(() -> execute(connection,
                    "insert into catalog.service (workspace_id,id,profile_id,slug,status) values ('"
                    + secondWorkspace + "','" + UUID.randomUUID() + "','" + crossProfileId + "','cross-tenant','draft')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_service__profile__owner");
            connection.rollback();

            connection.setAutoCommit(false);
            firstWorkspace = seedWorkspace(connection, "23");
            UUID immutableProfileId = UUID.randomUUID();
            UUID workflowId = UUID.randomUUID();
            UUID serviceId = UUID.randomUUID();
            UUID serviceVersionId = UUID.randomUUID();
            execute(connection, "insert into catalog.creator_profile (workspace_id,id,slug,draft_json,intake_status) values ('"
                    + firstWorkspace + "','" + immutableProfileId + "','immutable-profile','{}','open')");
            execute(connection, "insert into catalog.workflow_template_version (workspace_id,id,template_id,version_no,name,status,published_at) values ('"
                    + firstWorkspace + "','" + workflowId + "','" + UUID.randomUUID() + "',1,'Dummy','published',now())");
            execute(connection, "insert into catalog.service (workspace_id,id,profile_id,slug,status) values ('"
                    + firstWorkspace + "','" + serviceId + "','" + immutableProfileId + "','immutable-service','published')");
            execute(connection, "insert into catalog.service_version (workspace_id,id,service_id,version_no,content_json,workflow_version_id,status,published_at) values ('"
                    + firstWorkspace + "','" + serviceVersionId + "','" + serviceId + "',1,'{}','" + workflowId + "','published',now())");
            connection.commit();
            assertThatThrownBy(() -> execute(connection, "update catalog.service_version set content_json='{\"changed\":true}' where id='"
                    + serviceVersionId + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }
    }

    private static UUID seedWorkspace(java.sql.Connection connection, String digestByte) throws SQLException {
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        execute(connection, "insert into iam.account (id,email_digest_v2,email_ciphertext,status,auth_generation) values ('"
                + accountId + "',decode(repeat('" + digestByte + "',32),'hex'),'{}','active',0)");
        execute(connection, "insert into workspace.workspace (id,owner_account_id,name,status,billing_mode) values ('"
                + workspaceId + "','" + accountId + "','Dummy','active','trial')");
        execute(connection, "insert into workspace.membership (workspace_id,id,account_id,role,status,joined_at) values ('"
                + workspaceId + "','" + UUID.randomUUID() + "','" + accountId + "','owner','active',now())");
        return workspaceId;
    }

    private static byte[] hashByte(int value) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, (byte) value);
        return hash;
    }

    private static void insertOutbox(JdbcTemplate jdbc, UUID eventId, UUID workspaceId,
            UUID aggregateId, UUID correlationId, long sequence, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO infra.outbox_event (
                    event_id, workspace_id, producer, aggregate_type, aggregate_id, aggregate_sequence,
                    event_type, payload, correlation_id, actor, occurred_at, available_at
                ) VALUES (?, ?, 'test', 'Aggregate', ?, ?, 'test.created.v1', '{}'::jsonb,
                          ?, '{}'::jsonb, ?, ?)
                """, eventId, workspaceId, aggregateId, sequence, correlationId,
                java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt));
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Flyway migrateFresh(String url, String username, String password) {
        var managedSchemas = new ArrayList<>(SCHEMAS);
        managedSchemas.add("public");
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .cleanDisabled(false)
                .defaultSchema("public")
                .schemas(managedSchemas.toArray(String[]::new))
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        return flyway;
    }

    private static final class UUIDs {
        private final java.util.UUID actor = java.util.UUID.randomUUID();
        private final java.util.UUID correlation = java.util.UUID.randomUUID();
        private final java.util.UUID workspace = java.util.UUID.randomUUID();
        private final java.util.UUID aggregate = java.util.UUID.randomUUID();
        private java.util.UUID auditEvent;
    }

    private static void requireDedicatedDummyDatabase(String jdbcUrl) {
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String databaseName = uri.getPath().replaceFirst("^/", "");
        if (!"stage_accord_dummy".equals(databaseName)) {
            throw new IllegalStateException("Database integration tests require stage_accord_dummy");
        }
    }
}
