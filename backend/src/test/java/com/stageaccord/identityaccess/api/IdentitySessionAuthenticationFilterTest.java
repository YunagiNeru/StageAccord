package com.stageaccord.identityaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.Cookie;

class IdentitySessionAuthenticationFilterTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void translatesOnlyAValidatedServerSessionIntoSpringAuthentication() throws Exception {
        UUID accountId = UUID.randomUUID();
        IdentityAccessGateway identities = mock(IdentityAccessGateway.class);
        when(identities.resolve("opaque-token")).thenReturn(
                new AuthenticatedPrincipal(accountId, new byte[32], "passkey", Instant.now()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(IdentityController.SESSION_COOKIE, "opaque-token"));

        new IdentitySessionAuthenticationFilter(identities).doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(accountId.toString());
    }
}
