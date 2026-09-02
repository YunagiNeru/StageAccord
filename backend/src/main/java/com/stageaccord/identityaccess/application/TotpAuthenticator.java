package com.stageaccord.identityaccess.application;

import java.time.Instant;

public interface TotpAuthenticator {
    String issueSecret();

    boolean verify(String secret, String code, Instant now);
}
