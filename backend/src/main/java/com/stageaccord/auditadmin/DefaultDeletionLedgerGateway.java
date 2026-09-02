package com.stageaccord.auditadmin;

import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.stageaccord.auditadmin.api.DeletionLedgerGateway;
import com.stageaccord.auditadmin.application.IndependentDeletionLedger;

@Component
public final class DefaultDeletionLedgerGateway implements DeletionLedgerGateway {
    private final IndependentDeletionLedger ledger;private final String keyId;private final PrivateKey privateKey;private final PublicKey publicKey;
    public DefaultDeletionLedgerGateway(@Value("${stage-accord.privacy.ledger.path}")String path,
            @Value("${stage-accord.privacy.ledger.key-id}")String keyId,
            @Value("${stage-accord.privacy.ledger.private-key}")String privateKey,
            @Value("${stage-accord.privacy.ledger.public-key}")String publicKey){try{var factory=KeyFactory.getInstance("Ed25519");this.ledger=
                new IndependentDeletionLedger(Path.of(path));this.keyId=keyId;this.privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(privateKey)));this.publicKey=factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        }catch(Exception failure){throw new IllegalStateException("deletion ledger key configuration is invalid",failure);}}
    @Override public Receipt appendDelete(byte[] subjectDigest,byte[] canonicalPayload,Instant occurredAt){try{var entry=ledger.append(
                IndependentDeletionLedger.Action.DELETE,subjectDigest,canonicalPayload,occurredAt,keyId,privateKey,publicKey);return new Receipt(UUID.randomUUID(),
                entry.previousHash(),entry.entryHash(),entry.keyId(),entry.signature());}catch(Exception failure){throw new LedgerUnavailable(failure);}}
    public static final class LedgerUnavailable extends RuntimeException{LedgerUnavailable(Throwable cause){super(cause);}}
}
