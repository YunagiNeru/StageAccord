package com.stageaccord.identityaccess;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.identityaccess.api.IdentityAccessGateway;
import com.stageaccord.identityaccess.application.AuthChallenge;
import com.stageaccord.identityaccess.application.IdentityApplicationException;
import com.stageaccord.identityaccess.application.IdentityAccessService;
import com.stageaccord.identityaccess.application.IdentitySecretStore;
import com.stageaccord.identityaccess.application.IdentityStore;
import com.stageaccord.identityaccess.application.IssuedToken;
import com.stageaccord.sharedkernel.application.AuditRecorder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PasskeyEnrollmentService {
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final IdentityAccessGateway access;
    private final IdentityStore store;
    private final IdentitySecretStore secrets;
    private final WebAuthnRelyingPartyOperations relyingParty;
    private final UserCredentialRepository credentials;
    private final ObjectMapper json;
    private final AuditRecorder audit;
    private final IdentityAccessService identityService;
    private final Clock clock;

    public PasskeyEnrollmentService(IdentityAccessGateway access, IdentityStore store,
            IdentitySecretStore secrets, WebAuthnRelyingPartyOperations relyingParty,
            UserCredentialRepository credentials, ObjectMapper json, AuditRecorder audit,
            IdentityAccessService identityService) {
        this(access, store, secrets, relyingParty, credentials, json, audit, identityService, Clock.systemUTC());
    }

    PasskeyEnrollmentService(IdentityAccessGateway access, IdentityStore store,
            IdentitySecretStore secrets, WebAuthnRelyingPartyOperations relyingParty,
            UserCredentialRepository credentials, ObjectMapper json, AuditRecorder audit,
            IdentityAccessService identityService, Clock clock) {
        this.access = access;
        this.store = store;
        this.secrets = secrets;
        this.relyingParty = relyingParty;
        this.credentials = credentials;
        this.json = json;
        this.audit = audit;
        this.identityService = identityService;
        this.clock = clock;
    }

    @Transactional
    public EnrollmentStart start(String sessionToken, UUID accountId, UUID emailChallengeId, String emailToken) {
        UUID resolvedAccountId = sessionToken == null || sessionToken.isBlank()
                ? identityService.verifyEnrollmentAccount(accountId, emailChallengeId, emailToken)
                : access.resolve(sessionToken).accountId();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                resolvedAccountId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        PublicKeyCredentialCreationOptions options = relyingParty
                .createPublicKeyCredentialCreationOptions(() -> authentication);
        IssuedToken token = secrets.issueToken();
        UUID id = UUID.randomUUID();
        store.createChallenge(new AuthChallenge(id, resolvedAccountId, "passkey_enrollment",
                token.digest(), token.digestKeyId(), null, secrets.protect(write(options)),
                clock.instant().plus(LIFETIME), null));
        audit.recordAllowed("StartPasskeyEnrollment", resolvedAccountId, null);
        return new EnrollmentStart(id, token.plaintext(), options);
    }

    @Transactional
    public EnrollmentResult confirm(UUID enrollmentId, String token, RelyingPartyPublicKey publicKey) {
        AuthChallenge challenge = store.lockChallenge(enrollmentId)
                .filter(item -> "passkey_enrollment".equals(item.purpose()))
                .filter(item -> item.consumedAt() == null && clock.instant().isBefore(item.expiresAt()))
                .filter(item -> java.security.MessageDigest.isEqual(item.tokenDigest(), secrets.tokenDigest(token)))
                .orElseThrow(() -> IdentityApplicationException.of(
                        IdentityApplicationException.Code.INVALID_CHALLENGE));
        PublicKeyCredentialCreationOptions options = read(secrets.reveal(challenge.protectedSubject()));
        var registered = relyingParty.registerCredential(
                new ImmutableRelyingPartyRegistrationRequest(options, publicKey));
        UUID credentialId = UUID.randomUUID();
        store.createPasskey(challenge.accountId(), credentialId,
                registered.getCredentialId().toBase64UrlString());
        store.consumeChallenge(enrollmentId, clock.instant());
        IdentityAccessService.EnrollmentCompletion completion =
                identityService.completePasskeyEnrollment(challenge.accountId());
        audit.recordAllowed("ConfirmPasskeyEnrollment", challenge.accountId(), null);
        return new EnrollmentResult(credentialId, completion);
    }

    @Transactional
    public void delete(String sessionToken, UUID credentialId) {
        var principal = access.resolve(sessionToken);
        boolean alternate = store.countActiveCredentials(principal.accountId(), "passkey") > 1
                || (store.countActiveCredentials(principal.accountId(), "password") > 0
                    && store.countActiveCredentials(principal.accountId(), "totp") > 0);
        if (!alternate) throw IdentityApplicationException.of(
                IdentityApplicationException.Code.BUSINESS_RULE_VIOLATION);
        String externalId = store.findPasskeyExternalId(principal.accountId(), credentialId)
                .orElseThrow(() -> IdentityApplicationException.of(
                        IdentityApplicationException.Code.BUSINESS_RULE_VIOLATION));
        credentials.delete(Bytes.fromBase64(externalId));
        store.revokeCredentials(principal.accountId(), "passkey", credentialId);
        store.advanceAuthGeneration(principal.accountId());
        store.revokeAllSessions(principal.accountId(), clock.instant());
        audit.recordAllowed("DeletePasskey", principal.accountId(), null);
    }

    private String write(PublicKeyCredentialCreationOptions value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("WebAuthn options serialization failed", failure); }
    }

    private PublicKeyCredentialCreationOptions read(String value) {
        try { return json.readValue(value, PublicKeyCredentialCreationOptions.class); }
        catch (JacksonException failure) { throw new IllegalStateException("WebAuthn options verification failed", failure); }
    }

    public record EnrollmentStart(UUID enrollmentId, String token,
            PublicKeyCredentialCreationOptions options) {}
    public record EnrollmentResult(UUID credentialId, IdentityAccessService.EnrollmentCompletion completion) {}
}
