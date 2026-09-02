package com.stageaccord.identityaccess;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import com.stageaccord.identityaccess.api.AuthenticatedPrincipal;
import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.application.IdentityApplicationException;
import com.stageaccord.identityaccess.application.IdentitySecretStore;
import com.stageaccord.identityaccess.application.IdentityStore;
import com.stageaccord.sharedkernel.application.AuditRecorder;

import tools.jackson.databind.ObjectMapper;

class PasskeyEnrollmentServiceTest {
    @Test
    void refusesLastFactorRemovalAndRevokesAllSessionsAfterSafeRemoval() {
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        UUID accountId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        IdentityAccessGateway access = mock(IdentityAccessGateway.class);
        IdentityStore store = mock(IdentityStore.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        when(access.resolve("session")).thenReturn(
                new AuthenticatedPrincipal(accountId, new byte[32], "passkey", now));
        when(store.countActiveCredentials(accountId, "passkey")).thenReturn(1);
        var service = new PasskeyEnrollmentService(access, store, mock(IdentitySecretStore.class),
                null, credentials, new ObjectMapper(), mock(AuditRecorder.class),
                mock(com.stageaccord.identityaccess.application.IdentityAccessService.class),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete("session", credentialId))
                .isInstanceOf(IdentityApplicationException.class);
        verify(credentials, never()).delete(org.mockito.ArgumentMatchers.any());

        when(store.countActiveCredentials(accountId, "password")).thenReturn(1);
        when(store.countActiveCredentials(accountId, "totp")).thenReturn(1);
        when(store.findPasskeyExternalId(accountId, credentialId)).thenReturn(java.util.Optional.of("AQID"));
        service.delete("session", credentialId);

        verify(credentials).delete(Bytes.fromBase64("AQID"));
        verify(store).revokeCredentials(accountId, "passkey", credentialId);
        verify(store).advanceAuthGeneration(accountId);
        verify(store).revokeAllSessions(accountId, now);
    }
}
