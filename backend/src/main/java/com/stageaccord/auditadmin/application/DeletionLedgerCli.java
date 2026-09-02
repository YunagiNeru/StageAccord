package com.stageaccord.auditadmin.application;

import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class DeletionLedgerCli {
    private DeletionLedgerCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <ledger-path> <ed25519-public-key-base64>");
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(args[1])));
        var result = new IndependentDeletionLedger(Path.of(args[0])).verify(publicKey);
        if (!result.valid()) throw new IllegalStateException("ledger verification failed at sequence "
                + result.failedSequence());
        System.out.println("{\"verifiedEntries\":" + result.entries().size()
                + ",\"terminalHash\":\"" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(result.terminalHash()) + "\"}");
    }
}
