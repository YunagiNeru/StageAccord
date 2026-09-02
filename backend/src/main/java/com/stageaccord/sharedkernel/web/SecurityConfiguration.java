package com.stageaccord.sharedkernel.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.api.IdentitySessionAuthenticationFilter;
import com.stageaccord.identityaccess.api.PasskeyAuthenticationSuccessHandler;
import com.stageaccord.identityaccess.application.IdentityAccessService;

@Configuration
@Profile("app")
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, IdentityAccessGateway identities,
            IdentityAccessService identityService,
            @Value("${stage-accord.webauthn.rp-id}") String rpId,
            @Value("${stage-accord.webauthn.allowed-origins}") String allowedOrigins) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.path("/").secure(true).sameSite("Lax"));
        Set<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).collect(Collectors.toUnmodifiableSet());
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(configuration -> configuration
                        .csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .webAuthn(configuration -> configuration.rpName("Stage Accord").rpId(rpId)
                        .allowedOrigins(origins)
                        .withObjectPostProcessor(new ObjectPostProcessor<WebAuthnAuthenticationFilter>() {
                            @Override
                            public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationSuccessHandler(
                                        new PasskeyAuthenticationSuccessHandler(identityService));
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/**", "/webauthn/**", "/login/webauthn",
                                "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(new IdentitySessionAuthenticationFilter(identities),
                        AnonymousAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieMaterializer(), AnonymousAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    @Bean
    PublicKeyCredentialUserEntityRepository webAuthnUsers(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository webAuthnCredentials(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }

    @Bean
    WebauthnJacksonModule webAuthnJson() { return new WebauthnJacksonModule(); }

    private static final class CsrfCookieMaterializer extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            filterChain.doFilter(request, response);
        }
    }
}
