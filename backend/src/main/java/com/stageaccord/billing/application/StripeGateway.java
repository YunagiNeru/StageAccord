package com.stageaccord.billing.application;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface StripeGateway {
    Checkout createCheckout(UUID workspaceId,String existingCustomerId,String priceId,URI successUrl,URI cancelUrl);
    URI createPortal(String customerId,URI returnUrl);
    VerifiedEvent verifyAndRetrieve(String payload,String signature);

    record Checkout(String customerId,URI url){}
    record VerifiedEvent(String id,String type,Instant createdAt,String payload,String apiPayload){}
}
