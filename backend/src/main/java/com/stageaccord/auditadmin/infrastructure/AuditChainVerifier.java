package com.stageaccord.auditadmin.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Properties;

public final class AuditChainVerifier {
    private AuditChainVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: AuditChainVerifier <configuration-file> <database-name>");
        }
        Properties properties = load(Path.of(args[0]));
        String jdbcUrl = databaseUrl(required(properties, "stage-accord.database.source-url"), args[1]);
        Verification result = verify(jdbcUrl,
                required(properties, "stage-accord.database.username"),
                required(properties, "stage-accord.database.password"));
        System.out.println(result.asJson());
        if (!result.valid()) throw new IllegalStateException("audit chain verification failed");
    }

    public static Verification verify(String jdbcUrl, String username, String password) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
                var statement = connection.prepareStatement("select * from audit.verify_chain()");
                var result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("audit verifier returned no result");
            return new Verification(
                    result.getBoolean("valid"),
                    result.getLong("checked_events"),
                    nullableLong(result, "failed_sequence"),
                    nullableLong(result, "terminal_sequence"),
                    result.getBytes("terminal_hash"));
        }
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing configuration key: " + key);
        return value.strip();
    }

    private static String databaseUrl(String sourceUrl, String databaseName) {
        if (!databaseName.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("invalid database name");
        }
        URI uri = URI.create(sourceUrl.replaceFirst("^jdbc:", ""));
        String replaced = sourceUrl.replace(uri.getPath(), "/" + databaseName);
        return replaced;
    }

    private static Long nullableLong(java.sql.ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record Verification(boolean valid, long checkedEvents, Long failedSequence,
            Long terminalSequence, byte[] terminalHash) {
        public Verification {
            terminalHash = terminalHash == null ? null : terminalHash.clone();
        }

        @Override public byte[] terminalHash() {
            return terminalHash == null ? null : terminalHash.clone();
        }

        public String asJson() {
            String hash = terminalHash == null ? "null" : "\"" + HexFormat.of().formatHex(terminalHash) + "\"";
            return "{\"valid\":" + valid
                    + ",\"checkedEvents\":" + checkedEvents
                    + ",\"failedSequence\":" + jsonNumber(failedSequence)
                    + ",\"terminalSequence\":" + jsonNumber(terminalSequence)
                    + ",\"terminalHash\":" + hash + "}";
        }

        private static String jsonNumber(Long value) { return value == null ? "null" : value.toString(); }
    }
}
