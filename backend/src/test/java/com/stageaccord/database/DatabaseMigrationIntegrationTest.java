package com.stageaccord.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
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
