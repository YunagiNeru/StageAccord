package com.stageaccord.identityaccess.infrastructure;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.application.TotpAuthenticator;

@Component
public final class Rfc6238TotpAuthenticator implements TotpAuthenticator {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int SECRET_BYTES = 20;
    private static final long STEP_SECONDS = 30;

    private final SecureRandom random;

    public Rfc6238TotpAuthenticator() {
        this(new SecureRandom());
    }

    Rfc6238TotpAuthenticator(SecureRandom random) {
        this.random = random;
    }

    @Override
    public String issueSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return encodeBase32(secret);
    }

    @Override
    public boolean verify(String secret, String code, Instant now) {
        if (code == null || !code.matches("[0-9]{6}")) return false;
        byte[] expected = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long step = now.getEpochSecond() / STEP_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            byte[] candidate = generate(decodeBase32(secret), step + offset)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, candidate)) return true;
        }
        return false;
    }

    private static String generate(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP unavailable", exception);
        }
    }

    static String encodeBase32(byte[] source) {
        StringBuilder result = new StringBuilder((source.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : source) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) result.append(BASE32[(buffer << (5 - bits)) & 31]);
        return result.toString();
    }

    private static byte[] decodeBase32(String encoded) {
        if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException("TOTP secret is required");
        String normalized = encoded.strip().replace("=", "").toUpperCase(java.util.Locale.ROOT);
        byte[] result = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int position = 0; position < normalized.length(); position++) {
            int value = alphabetIndex(normalized.charAt(position));
            if (value < 0) throw new IllegalArgumentException("invalid TOTP secret");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                result[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return result;
    }

    private static int alphabetIndex(char character) {
        if (character >= 'A' && character <= 'Z') return character - 'A';
        if (character >= '2' && character <= '7') return character - '2' + 26;
        return -1;
    }
}
