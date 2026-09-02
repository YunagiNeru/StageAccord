package com.stageaccord.billing.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stageaccord.billing.BillingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Validated @RestController @Profile("app") @RequestMapping("/api/v1")
public final class BillingController {
    private static final String SESSION="__Host-stageaccord-session";private final BillingService billing;
    public BillingController(BillingService billing){this.billing=billing;}
    @PostMapping("/workspaces/{workspaceId}/billing/checkout-sessions")
    public LocationResponse createCheckoutSession(@CookieValue(value=SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @Valid @RequestBody CheckoutRequest request){return new LocationResponse(billing.createCheckoutSession(session,workspaceId,request.planKey(),
                    request.successUrl(),request.cancelUrl()));}
    @PostMapping("/workspaces/{workspaceId}/billing/portal-sessions")
    public LocationResponse createPortalSession(@CookieValue(value=SESSION,required=false)String session,@PathVariable UUID workspaceId,
            @Valid @RequestBody PortalRequest request){return new LocationResponse(billing.createPortalSession(session,workspaceId,request.returnUrl()));}
    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Void> acceptStripeWebhook(@RequestHeader("Stripe-Signature")String signature,@RequestBody String payload){
        billing.acceptStripeWebhook(payload,signature);return ResponseEntity.noContent().build();}
    @GetMapping("/workspaces/{workspaceId}/capabilities")
    public JsonNode getWorkspaceCapabilities(@CookieValue(value=SESSION,required=false)String session,@PathVariable UUID workspaceId){
        return billing.getWorkspaceCapabilities(session,workspaceId);}
    @GetMapping("/workspaces/{workspaceId}/billing/summary")
    public JsonNode getBillingSummary(@CookieValue(value=SESSION,required=false)String session,@PathVariable UUID workspaceId){
        return billing.getBillingSummary(session,workspaceId);}
    public record LocationResponse(URI url){}public record CheckoutRequest(@NotBlank String planKey,@NotNull URI successUrl,@NotNull URI cancelUrl){}
    public record PortalRequest(@NotNull URI returnUrl){}
}
