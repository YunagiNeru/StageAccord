package com.stageaccord.auditadmin.application;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class IndependentDeletionLedger {
    private static final byte[] ZERO_HASH = new byte[32];
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final Path path;

    public IndependentDeletionLedger(Path path) {
        this.path = path;
    }

    public LedgerEntry append(Action action, byte[] subjectDigest, byte[] canonicalPayload,
            Instant occurredAt, String keyId, PrivateKey privateKey, PublicKey publicKey) throws Exception {
        require(subjectDigest != null && subjectDigest.length == 32 && canonicalPayload != null
                && occurredAt != null && keyId != null && !keyId.isBlank());
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE); var ignored = channel.lock()) {
            Verification verification = verifyLines(readLines(channel), publicKey);
            if (!verification.valid()) throw new IllegalStateException("ledger verification failed");
            long sequence = verification.entries().size() + 1L;
            byte[] previousHash = verification.entries().isEmpty()
                    ? ZERO_HASH : verification.entries().getLast().entryHash();
            String unsigned = unsigned(sequence, action, subjectDigest, canonicalPayload, occurredAt, previousHash, keyId);
            byte[] entryHash = sha256(unsigned.getBytes(StandardCharsets.UTF_8));
            byte[] signature = sign(entryHash, privateKey);
            String line = unsigned + "\t" + encode(entryHash) + "\t" + encode(signature) + "\n";
            channel.position(channel.size());
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new LedgerEntry(sequence, action, subjectDigest, canonicalPayload, occurredAt,
                    previousHash, entryHash, keyId, signature);
        }
    }

    public Verification verify(PublicKey publicKey) throws Exception {
        if (!Files.exists(path)) return new Verification(true, List.of(), ZERO_HASH, null);
        return verifyLines(Files.readAllLines(path, StandardCharsets.UTF_8), publicKey);
    }

    public ReapplyResult reapply(PublicKey publicKey, List<RestorationTarget> targets) throws Exception {
        Verification verification = verify(publicKey);
        if (!verification.valid()) throw new IllegalStateException("ledger verification failed at sequence "
                + verification.failedSequence());
        for (LedgerEntry entry : verification.entries()) {
            for (RestorationTarget target : targets) target.apply(entry);
        }
        return new ReapplyResult(verification.entries().size(), verification.terminalHash());
    }

    private static Verification verifyLines(List<String> lines, PublicKey publicKey) throws Exception {
        var entries = new ArrayList<LedgerEntry>();
        byte[] expectedPrevious = ZERO_HASH;
        long expectedSequence = 1;
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                String[] fields = line.split("\\t", -1);
                require(fields.length == 9);
                long sequence = Long.parseLong(fields[0]);
                Action action = Action.valueOf(fields[1]);
                byte[] subject = decode(fields[2]);
                byte[] payload = decode(fields[3]);
                Instant occurredAt = Instant.ofEpochMilli(Long.parseLong(fields[4]));
                byte[] previous = decode(fields[5]);
                String keyId = new String(decode(fields[6]), StandardCharsets.UTF_8);
                byte[] hash = decode(fields[7]);
                byte[] signature = decode(fields[8]);
                String unsigned = String.join("\t", java.util.Arrays.copyOf(fields, 7));
                if (sequence != expectedSequence || subject.length != 32 || previous.length != 32
                        || hash.length != 32 || signature.length != 64
                        || !MessageDigest.isEqual(previous, expectedPrevious)
                        || !MessageDigest.isEqual(hash, sha256(unsigned.getBytes(StandardCharsets.UTF_8)))
                        || !verifySignature(hash, signature, publicKey)) {
                    return new Verification(false, List.copyOf(entries), expectedPrevious, expectedSequence);
                }
                entries.add(new LedgerEntry(sequence, action, subject, payload, occurredAt,
                        previous, hash, keyId, signature));
                expectedPrevious = hash;
                expectedSequence++;
            } catch (RuntimeException exception) {
                return new Verification(false, List.copyOf(entries), expectedPrevious, expectedSequence);
            }
        }
        return new Verification(true, List.copyOf(entries), expectedPrevious, null);
    }

    private static List<String> readLines(FileChannel channel) throws IOException {
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        buffer.flip();
        String content = StandardCharsets.UTF_8.decode(buffer).toString();
        return content.isEmpty() ? List.of() : content.lines().toList();
    }

    private static String unsigned(long sequence, Action action, byte[] subject, byte[] payload,
            Instant occurredAt, byte[] previousHash, String keyId) {
        return sequence + "\t" + action + "\t" + encode(subject) + "\t" + encode(payload) + "\t"
                + occurredAt.toEpochMilli() + "\t" + encode(previousHash) + "\t"
                + encode(keyId.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sign(byte[] hash, PrivateKey key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(hash);
        return signer.sign();
    }

    private static boolean verifySignature(byte[] hash, byte[] signature, PublicKey key) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(hash);
        return verifier.verify(signature);
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String encode(byte[] value) { return ENCODER.encodeToString(value); }
    private static byte[] decode(String value) { return DECODER.decode(value); }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("invalid ledger entry");
    }

    public enum Action { DELETE, UNPUBLISH, REVOKE_ACCESS, LEGAL_HOLD, RELEASE_HOLD }
    public record LedgerEntry(long sequence, Action action, byte[] subjectDigest, byte[] canonicalPayload,
            Instant occurredAt, byte[] previousHash, byte[] entryHash, String keyId, byte[] signature) {
        public LedgerEntry {
            subjectDigest = subjectDigest.clone(); canonicalPayload = canonicalPayload.clone();
            previousHash = previousHash.clone(); entryHash = entryHash.clone(); signature = signature.clone();
        }
        @Override public byte[] subjectDigest() { return subjectDigest.clone(); }
        @Override public byte[] canonicalPayload() { return canonicalPayload.clone(); }
        @Override public byte[] previousHash() { return previousHash.clone(); }
        @Override public byte[] entryHash() { return entryHash.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }
    public record Verification(boolean valid, List<LedgerEntry> entries, byte[] terminalHash,
            Long failedSequence) {
        public Verification { entries = List.copyOf(entries); terminalHash = terminalHash.clone(); }
        @Override public byte[] terminalHash() { return terminalHash.clone(); }
    }
    public record ReapplyResult(int appliedEntries, byte[] terminalHash) {
        public ReapplyResult { terminalHash = terminalHash.clone(); }
        @Override public byte[] terminalHash() { return terminalHash.clone(); }
    }
    @FunctionalInterface public interface RestorationTarget { void apply(LedgerEntry entry) throws Exception; }
}
