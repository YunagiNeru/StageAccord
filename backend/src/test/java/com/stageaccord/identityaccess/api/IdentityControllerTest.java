package com.stageaccord.identityaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.stageaccord.identityaccess.application.IdentityAccessService;
import com.stageaccord.identityaccess.application.SessionDescriptor;
import com.stageaccord.identityaccess.domain.AuthStrength;

class IdentityControllerTest {
    private final IdentityAccessService identities = mock(IdentityAccessService.class);
    private final IdentityController controller = new IdentityController(identities);

    @Test
    void authenticationReturnsOnlyAHardenedHostCookie() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        SessionDescriptor descriptor = new SessionDescriptor(UUID.randomUUID(), accountId, new byte[32],
                "identity-v1", AuthStrength.PASSWORD_TOTP, 0, now, now,
                now.plusSeconds(3600), null);
        when(identities.authenticate("person@example.com", "password-value", "123456"))
                .thenReturn(new IdentityAccessService.IssuedSession("opaque-token", descriptor));

        var response = controller.authenticate(new IdentityController.AuthenticationRequest(
                "person@example.com", "password-value", "123456"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("__Host-stageaccord-session=opaque-token", "Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");
        assertThat(response.getBody()).isNull();
    }

    @Test
    void currentSessionResponseNeverContainsTokenDigest() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        when(identities.resolveSession("opaque-token")).thenReturn(new SessionDescriptor(
                sessionId, accountId, new byte[32], "identity-v1", AuthStrength.PASSWORD_TOTP,
                0, now, now, now.plusSeconds(3600), null));

        var response = controller.getCurrentSession("opaque-token");

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.authStrength()).isEqualTo("password_totp");
        assertThat(response.toString()).doesNotContain("opaque-token", "identity-v1");
    }

    @Test
    void logoutRevokesTheResolvedSessionAndExpiresTheCookie() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        when(identities.resolveSession("opaque-token")).thenReturn(new SessionDescriptor(
                sessionId, accountId, new byte[32], "identity-v1", AuthStrength.PASSWORD_TOTP,
                0, now, now, now.plusSeconds(3600), null));

        var response = controller.logout("opaque-token");

        verify(identities).logout("opaque-token");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("__Host-stageaccord-session=", "Max-Age=0", "Secure", "HttpOnly");
    }
}
