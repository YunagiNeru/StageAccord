package com.stageaccord.identityaccess.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.stageaccord.identityaccess.application.IdentityApplicationException;

class IdentityApiExceptionHandlerTest {
    private final IdentityApiExceptionHandler handler = new IdentityApiExceptionHandler();

    @Test
    void mapsIdentityFailuresToTheContractStatusWithoutDetails() {
        var unauthorized = handler.handleIdentityFailure(IdentityApplicationException.of(
                IdentityApplicationException.Code.AUTHENTICATION_REQUIRED));
        var consumed = handler.handleIdentityFailure(IdentityApplicationException.of(
                IdentityApplicationException.Code.CHALLENGE_CONSUMED));

        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getProperties()).containsEntry("code", "AUTHENTICATION_REQUIRED");
        assertThat(unauthorized.getDetail()).isNull();
        assertThat(consumed.getStatus()).isEqualTo(409);
    }
}
