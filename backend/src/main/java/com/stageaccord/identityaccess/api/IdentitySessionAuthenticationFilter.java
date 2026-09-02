package com.stageaccord.identityaccess.api;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.stageaccord.identityaccess.application.IdentityApplicationException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class IdentitySessionAuthenticationFilter extends OncePerRequestFilter {
    private final IdentityAccessGateway identities;

    public IdentitySessionAuthenticationFilter(IdentityAccessGateway identities) { this.identities = identities; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = cookie(request, IdentityController.SESSION_COOKIE);
        if (token != null) {
            try {
                AuthenticatedPrincipal principal = identities.resolve(token);
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal.accountId().toString(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            } catch (IdentityApplicationException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
