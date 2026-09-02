package com.stageaccord.identityaccess.api;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.stageaccord.identityaccess.application.IdentityAccessService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class PasskeyAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final IdentityAccessService identities;

    public PasskeyAuthenticationSuccessHandler(IdentityAccessService identities) { this.identities = identities; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        var issued = identities.issuePasskeySession(UUID.fromString(authentication.getName()));
        ResponseCookie cookie = ResponseCookie.from(IdentityController.SESSION_COOKIE, issued.token())
                .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(Duration.ofDays(7)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"authenticated\":true,\"redirectUrl\":\"/app\"}");
    }
}
