package com.stageaccord.billing.infrastructure;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.stageaccord.billing.application.StripeGateway;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

@Component
public final class StripeSdkGateway implements StripeGateway {
    private final StripeClient stripe;private final String webhookSecret;
    public StripeSdkGateway(@Value("${stage-accord.billing.stripe.api-key}")String apiKey,
            @Value("${stage-accord.billing.stripe.webhook-secret}")String webhookSecret){
        this.stripe=new StripeClient(apiKey);this.webhookSecret=webhookSecret;}
    @Override public Checkout createCheckout(UUID workspaceId,String existingCustomerId,String priceId,URI successUrl,URI cancelUrl){
        try{String customerId=existingCustomerId;if(customerId==null){Customer customer=stripe.v1().customers().create(
                CustomerCreateParams.builder().putMetadata("workspace_id",workspaceId.toString()).build());customerId=customer.getId();}
            Session session=stripe.v1().checkout().sessions().create(SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId).setSuccessUrl(successUrl.toString()).setCancelUrl(cancelUrl.toString())
                .putMetadata("workspace_id",workspaceId.toString()).addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build()).build());
            return new Checkout(customerId,URI.create(session.getUrl()));}catch(Exception failure){throw new StripeUnavailable(failure);}}
    @Override public URI createPortal(String customerId,URI returnUrl){try{var params=com.stripe.param.billingportal.SessionCreateParams.builder()
                .setCustomer(customerId).setReturnUrl(returnUrl.toString()).build();var session=stripe.v1().billingPortal().sessions().create(params);
            return URI.create(session.getUrl());}catch(Exception failure){throw new StripeUnavailable(failure);}}
    @Override public VerifiedEvent verifyAndRetrieve(String payload,String signature){try{Event signed=Webhook.constructEvent(payload,signature,webhookSecret);
            Event retrieved=stripe.v1().events().retrieve(signed.getId());if(!retrieved.getType().equals(signed.getType())||
                    !retrieved.getCreated().equals(signed.getCreated()))throw new StripeUnavailable();return new VerifiedEvent(signed.getId(),signed.getType(),
                    Instant.ofEpochSecond(signed.getCreated()),payload,retrieved.toJson());}catch(SignatureVerificationException failure){
            throw new StripeSignatureInvalid(failure);}catch(StripeSignatureInvalid failure){throw failure;}catch(Exception failure){throw new StripeUnavailable(failure);}}
    public static final class StripeSignatureInvalid extends RuntimeException{StripeSignatureInvalid(Throwable cause){super(cause);}}
    public static final class StripeUnavailable extends RuntimeException{StripeUnavailable(){super();}StripeUnavailable(Throwable cause){super(cause);}}
}
