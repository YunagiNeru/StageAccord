package com.stageaccord.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.DriverManager;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "STAGE_ACCORD_TEST_DB_URL", matches = ".+")
class DatabaseMigrationIntegrationTest {

    private static final Set<String> SCHEMAS = Set.of(
            "iam", "workspace", "catalog", "intake", "agreement", "project",
            "collab", "file_store", "privacy", "schedule", "billing", "audit");

    @Test
    void migrationCreatesEveryOwnedSchemaInDedicatedDummyDatabase() throws Exception {
        String url = System.getenv("STAGE_ACCORD_TEST_DB_URL");
        requireDedicatedDummyDatabase(url);
        String username = System.getenv("STAGE_ACCORD_TEST_DB_USERNAME");
        String password = System.getenv("STAGE_ACCORD_TEST_DB_PASSWORD");

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
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

    private static void requireDedicatedDummyDatabase(String jdbcUrl) {
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String databaseName = uri.getPath().replaceFirst("^/", "");
        if (!"stage_accord_dummy".equals(databaseName)) {
            throw new IllegalStateException("Database integration tests require stage_accord_dummy");
        }
    }
}
