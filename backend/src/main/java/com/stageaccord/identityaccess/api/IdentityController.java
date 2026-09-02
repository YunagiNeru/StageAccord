package com.stageaccord.identityaccess.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.identityaccess.application.IdentityAccessService;
import com.stageaccord.identityaccess.PasskeyEnrollmentService;
import com.stageaccord.identityaccess.application.SessionDescriptor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@Profile("app")
@RequestMapping("/api/v1/auth")
public final class IdentityController {
    static final String SESSION_COOKIE = "__Host-stageaccord-session";
    static final String CLIENT_SESSION_COOKIE = "__Host-stageaccord-client";
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);

    private final IdentityAccessService identities;
    private final PasskeyEnrollmentService passkeys;

    @Autowired
    public IdentityController(IdentityAccessService identities, PasskeyEnrollmentService passkeys) {
        this.identities = identities;
        this.passkeys = passkeys;
    }

    IdentityController(IdentityAccessService identities) { this(identities, null); }

    @PostMapping("/email-verifications")
    public ResponseEntity<Void> startEmailVerification(@Valid @RequestBody EmailRequest request) {
        identities.startEmailVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email-verifications/{id}/completions")
    public ResponseEntity<Void> completeEmailVerification(@PathVariable UUID id,
            @Valid @RequestBody TokenRequest request) {
        identities.completeEmailVerification(id, request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/registrations")
    public ResponseEntity<RegistrationResponse> registerCreator(@Valid @RequestBody RegistrationRequest request) {
        UUID accountId = identities.registerCreator(request.emailChallengeId(), request.emailChallengeToken(),
                request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegistrationResponse(accountId, "pending"));
    }

    @PostMapping("/totp-enrollments")
    public ResponseEntity<TotpEnrollmentResponse> startTotpEnrollment(
            @Valid @RequestBody StartTotpEnrollmentRequest request) {
        var enrollment = identities.startTotpEnrollment(request.accountId(), request.emailChallengeId(),
                request.emailChallengeToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TotpEnrollmentResponse(
                enrollment.enrollmentId(), enrollment.token(), enrollment.secret(), enrollment.expiresAt()));
    }

    @PostMapping("/totp-enrollments/{id}/confirmations")
    public ResponseEntity<RecoveryCodesResponse> confirmTotpEnrollment(@PathVariable UUID id,
            @Valid @RequestBody ConfirmTotpRequest request) {
        var completion = identities.confirmTotpEnrollment(id, request.token(), request.code());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(completion.session().token()).toString())
                .body(new RecoveryCodesResponse(completion.recoveryCodes()));
    }

    @PostMapping("/sessions")
    public ResponseEntity<Void> authenticate(@Valid @RequestBody AuthenticationRequest request) {
        var session = identities.authenticate(request.email(), request.password(), request.totpCode());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(session.token()).toString())
                .build();
    }

    @PostMapping("/re-authentications")
    public ResponseEntity<Void> reauthenticate(
            @CookieValue(value = SESSION_COOKIE, required = false) String token,
            @Valid @RequestBody ReauthenticationRequest request) {
        identities.reauthenticate(token, request.password(), request.totpCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/client-links/redemptions")
    public ResponseEntity<ClientLinkResponse> redeemClientLink(@Valid @RequestBody TokenRequest request) {
        var session = identities.redeemClientLink(request.token());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clientSessionCookie(session.token()).toString())
                .body(new ClientLinkResponse(session.sessionId(), session.projectId(), session.expiresAt()));
    }

    @PostMapping("/recoveries")
    public ResponseEntity<RecoveryStartedResponse> startRecovery(@Valid @RequestBody EmailRequest request) {
        return ResponseEntity.accepted().body(new RecoveryStartedResponse(identities.startRecovery(request.email())));
    }

    @PostMapping("/recoveries/{id}/completions")
    public ResponseEntity<RecoveryCompletedResponse> recoverAccount(@PathVariable UUID id,
            @Valid @RequestBody RecoveryRequest request) {
        var completed = identities.recoverAccount(id, request.recoveryCode(), request.newPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(completed.session().token()).toString())
                .body(new RecoveryCompletedResponse(completed.recoveryCodes()));
    }

    @GetMapping("/session")
    public CurrentSessionResponse getCurrentSession(@CookieValue(value = SESSION_COOKIE, required = false) String token) {
        return current(identities.resolveSession(token));
    }

    @GetMapping("/sessions")
    public List<CurrentSessionResponse> listSessions(
            @CookieValue(value = SESSION_COOKIE, required = false) String token) {
        return identities.listSessions(token).stream().map(IdentityController::current).toList();
    }

    @GetMapping("/factors")
    public List<AuthFactorResponse> listAuthFactors(
            @CookieValue(value = SESSION_COOKIE, required = false) String token) {
        return identities.listAuthFactors(token).stream()
                .map(item -> new AuthFactorResponse(item.credentialId(), item.type(), item.status())).toList();
    }

    @PostMapping("/passkey-enrollments")
    public PasskeyEnrollmentResponse startPasskeyEnrollment(
            @CookieValue(value = SESSION_COOKIE, required = false) String token,
            @Valid @RequestBody(required = false) StartPasskeyRequest request) {
        var started = passkeys.start(token, request == null ? null : request.accountId(),
                request == null ? null : request.emailChallengeId(),
                request == null ? null : request.emailChallengeToken());
        return new PasskeyEnrollmentResponse(started.enrollmentId(), started.token(), started.options());
    }

    @PostMapping("/passkey-enrollments/{id}/confirmations")
    public ResponseEntity<PasskeyResponse> confirmPasskeyEnrollment(
            @PathVariable UUID id, @Valid @RequestBody ConfirmPasskeyRequest request) {
        var result = passkeys.confirm(id, request.token(), request.publicKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.completion().session().token()).toString())
                .body(new PasskeyResponse(result.credentialId(), result.completion().recoveryCodes()));
    }

    @DeleteMapping("/passkeys/{credentialId}")
    public ResponseEntity<Void> deletePasskey(
            @CookieValue(value = SESSION_COOKIE, required = false) String session,
            @PathVariable UUID credentialId) {
        passkeys.delete(session, credentialId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/totp")
    public ResponseEntity<Void> deleteTotp(
            @CookieValue(value = SESSION_COOKIE, required = false) String token) {
        identities.deleteTotp(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recovery-code-rotations")
    public RecoveryCodesResponse rotateRecoveryCodes(
            @CookieValue(value = SESSION_COOKIE, required = false) String token) {
        return new RecoveryCodesResponse(identities.rotateRecoveryCodes(token));
    }

    @PutMapping("/password")
    public ResponseEntity<Void> replacePassword(
            @CookieValue(value = SESSION_COOKIE, required = false) String token,
            @Valid @RequestBody ReplacePasswordRequest request) {
        var session = identities.replacePassword(token, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(session.token()).toString()).build();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(
            @CookieValue(value = SESSION_COOKIE, required = false) String token,
            @PathVariable UUID sessionId) {
        identities.revokeSession(token, sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(
            @CookieValue(value = SESSION_COOKIE, required = false) String token) {
        identities.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .build();
    }

    private static CurrentSessionResponse current(SessionDescriptor session) {
        return new CurrentSessionResponse(session.id(), session.accountId(),
                session.strength().name().toLowerCase(java.util.Locale.ROOT),
                session.authenticatedAt(), session.lastSeenAt(), session.absoluteExpiresAt(),
                session.revokedAt() != null);
    }

    private static ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(SESSION_COOKIE, token).httpOnly(true).secure(true)
                .sameSite("Lax").path("/").maxAge(SESSION_LIFETIME).build();
    }

    private static ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "").httpOnly(true).secure(true)
                .sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie clientSessionCookie(String token) {
        return ResponseCookie.from(CLIENT_SESSION_COOKIE, token).httpOnly(true).secure(true)
                .sameSite("Lax").path("/").maxAge(Duration.ofHours(24)).build();
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {}
    public record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    public record RegistrationRequest(@NotNull UUID emailChallengeId,
            @NotBlank @Size(max = 256) String emailChallengeToken,
            @NotBlank @Email @Size(max = 320) String email,
            @Size(min = 12, max = 128) String password) {}
    public record RegistrationResponse(UUID accountId, String status) {}
    public record StartTotpEnrollmentRequest(@NotNull UUID accountId, @NotNull UUID emailChallengeId,
            @NotBlank @Size(max = 256) String emailChallengeToken) {}
    public record TotpEnrollmentResponse(UUID enrollmentId, String token, String secret, Instant expiresAt) {}
    public record ConfirmTotpRequest(@NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 6, max = 6) String code) {}
    public record RecoveryCodesResponse(List<String> recoveryCodes) {}
    public record AuthenticationRequest(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(min = 6, max = 6) String totpCode) {}
    public record CurrentSessionResponse(UUID sessionId, UUID accountId, String authStrength,
            Instant authenticatedAt, Instant lastSeenAt, Instant absoluteExpiresAt, boolean revoked) {}
    public record ReauthenticationRequest(@NotBlank @Size(max = 128) String password,
            @NotBlank @Size(min = 6, max = 6) String totpCode) {}
    public record ClientLinkResponse(UUID sessionId, UUID projectId, Instant expiresAt) {}
    public record RecoveryStartedResponse(UUID recoveryId) {}
    public record RecoveryRequest(@NotBlank @Size(max = 256) String recoveryCode,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record RecoveryCompletedResponse(List<String> recoveryCodes) {}
    public record AuthFactorResponse(UUID credentialId, String type, String status) {}
    public record ReplacePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record PasskeyEnrollmentResponse(UUID enrollmentId, String token,
            PublicKeyCredentialCreationOptions options) {}
    public record StartPasskeyRequest(UUID accountId, UUID emailChallengeId,
            @Size(max = 256) String emailChallengeToken) {}
    public record ConfirmPasskeyRequest(@NotBlank @Size(max = 256) String token,
            @NotNull RelyingPartyPublicKey publicKey) {}
    public record PasskeyResponse(UUID credentialId, List<String> recoveryCodes) {}
}
