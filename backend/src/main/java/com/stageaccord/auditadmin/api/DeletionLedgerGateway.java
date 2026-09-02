package com.stageaccord.auditadmin.api;

import java.time.Instant;
import java.util.UUID;

public interface DeletionLedgerGateway {
    Receipt appendDelete(byte[] subjectDigest,byte[] canonicalPayload,Instant occurredAt);
    record Receipt(UUID entryId,byte[] previousHash,byte[] entryHash,String keyId,byte[] signature){
        public Receipt{previousHash=previousHash.clone();entryHash=entryHash.clone();signature=signature.clone();}
        @Override public byte[] previousHash(){return previousHash.clone();}@Override public byte[] entryHash(){return entryHash.clone();}
        @Override public byte[] signature(){return signature.clone();}}
}
