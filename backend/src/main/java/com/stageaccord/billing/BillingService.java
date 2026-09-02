package com.stageaccord.billing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stageaccord.billing.application.StripeGateway;
import com.stageaccord.billing.domain.BillingPolicy;
import com.stageaccord.sharedkernel.application.AuditRecorder;
import com.stageaccord.sharedkernel.web.ApiFailure;
import com.stageaccord.workspacemembership.api.WorkspaceAccess;
import com.stageaccord.workspacemembership.api.WorkspaceAccessGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BillingService {
    private static final Set<WorkspaceAccess> BILLING=Set.of(WorkspaceAccess.OWNER,WorkspaceAccess.BILLING_ADMIN);
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final StripeGateway stripe;
    private final WorkspaceAccessGateway workspaces;private final AuditRecorder audit;private final Clock clock=Clock.systemUTC();
    private final Map<String,String> prices;
    public BillingService(JdbcTemplate jdbc,ObjectMapper json,StripeGateway stripe,WorkspaceAccessGateway workspaces,
            AuditRecorder audit,@Value("${stage-accord.billing.stripe.price.creator-pro-monthly}")String creatorMonthly,
            @Value("${stage-accord.billing.stripe.price.creator-pro-yearly}")String creatorYearly,
            @Value("${stage-accord.billing.stripe.price.studio-monthly}")String studioMonthly,
            @Value("${stage-accord.billing.stripe.price.studio-yearly}")String studioYearly){this.jdbc=jdbc;this.json=json;this.stripe=stripe;
        this.workspaces=workspaces;this.audit=audit;this.prices=Map.of("creator-pro-monthly",creatorMonthly,"creator-pro-yearly",creatorYearly,
                "studio-monthly",studioMonthly,"studio-yearly",studioYearly);}
    @Transactional public URI createCheckoutSession(String session,UUID workspaceId,String planKey,URI success,URI cancel){UUID actor=
        workspaces.requireMember(session,workspaceId,BILLING).accountId();String price=prices.get(planKey);if(price==null)throw invalid("UNKNOWN_PLAN");
        Customer current=customer(workspaceId);StripeGateway.Checkout checkout;try{checkout=stripe.createCheckout(workspaceId,current==null?null:current.stripeId(),price,success,cancel);}
        catch(RuntimeException failure){throw unavailable();}if(current==null)jdbc.update("INSERT INTO billing.customer(workspace_id,id,stripe_customer_id,created_at,status) "
                +"VALUES (?,?,?,?,'active')",workspaceId,UUID.randomUUID(),checkout.customerId(),clock.instant());audit.recordAllowed("CreateCheckoutSession",actor,workspaceId);
        return checkout.url();}
    @Transactional public URI createPortalSession(String session,UUID workspaceId,URI returnUrl){UUID actor=
        workspaces.requireMember(session,workspaceId,BILLING).accountId();Customer customer=customer(workspaceId);if(customer==null)throw notFound();
        try{URI url=stripe.createPortal(customer.stripeId(),returnUrl);audit.recordAllowed("CreatePortalSession",actor,workspaceId);return url;}
        catch(RuntimeException failure){throw unavailable();}}
    @Transactional public void acceptStripeWebhook(String payload,String signature){StripeGateway.VerifiedEvent event;try{event=stripe.verifyAndRetrieve(payload,signature);}
        catch(com.stageaccord.billing.infrastructure.StripeSdkGateway.StripeSignatureInvalid failure){throw invalid("INVALID_STRIPE_SIGNATURE");}
        catch(RuntimeException failure){throw unavailable();}if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM billing.stripe_event WHERE stripe_event_id=?)",Boolean.class,event.id()))return;
        JsonNode root=read(event.apiPayload());JsonNode object=root.path("data").path("object");String customerId=text(object,"customer");if(customerId==null)throw invalid("STRIPE_CUSTOMER_MISSING");
        Customer customer=jdbc.query("SELECT workspace_id,id,stripe_customer_id FROM billing.customer WHERE stripe_customer_id=?",
                (r,n)->new Customer(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),customerId).stream().findFirst().orElseThrow(BillingService::notFound);
        UUID workspaceId=customer.workspaceId();String subscriptionId=event.type().startsWith("customer.subscription.")?text(object,"id"):text(object,"subscription");
        String providerStatus=text(object,"status");Instant providerUpdated=event.createdAt();BillingPolicy.EntitlementState requested=state(providerStatus,event.type());
        Subscription current=subscription(workspaceId);BillingPolicy.EntitlementState resolved=current==null?requested:new BillingPolicy().resolveWebhook(
                new BillingPolicy.CurrentEntitlement(current.state(),current.updatedAt()),new BillingPolicy.VerifiedEvent(true,true,true,providerUpdated,requested));
        boolean subscriptionEvent=event.type().startsWith("customer.subscription.");
        boolean stale=!subscriptionEvent||(current!=null&&!providerUpdated.isAfter(current.updatedAt()));String result=stale?"stale":"applied";Instant applied=stale?null:clock.instant();
        if(!stale){String plan=planForPrice(text(object.path("items").path("data").path(0).path("price"),"id"));Instant period=Instant.ofEpochSecond(
                object.path("current_period_end").asLong(clock.instant().plusSeconds(30L*86400).getEpochSecond()));upsertSubscription(customer,subscriptionId,plan,resolved,period,providerUpdated);
        }
        jdbc.update("INSERT INTO billing.stripe_event(workspace_id,id,stripe_event_id,stripe_created_at,event_type,payload_sha256,"
                +"signature_verified,api_verified,received_at,applied_at,result) VALUES (?,?,?,?,?,?,true,true,?,?,?)",workspaceId,UUID.randomUUID(),event.id(),
                event.createdAt(),event.type(),sha256(event.payload()),clock.instant(),applied,result);audit.recordAllowed("AcceptStripeWebhook",null,workspaceId);}
    @Transactional(readOnly=true) public JsonNode getWorkspaceCapabilities(String session,UUID workspaceId){workspaces.requireMember(session,workspaceId);
        Subscription current=subscription(workspaceId);BillingPolicy.EntitlementState state=current==null?BillingPolicy.EntitlementState.TRIAL:current.state();
        var cap=new BillingPolicy().capabilities(state);return json.createObjectNode().put("state",state.name().toLowerCase()).put("canRead",cap.canRead())
                .put("canExport",cap.canExport()).put("canUpdatePayment",cap.canUpdatePayment()).put("canCreate",cap.canCreate());}
    @Transactional(readOnly=true) public JsonNode getBillingSummary(String session,UUID workspaceId){workspaces.requireMember(session,workspaceId,BILLING);
        return jdbc.query("SELECT jsonb_build_object('planKey',s.plan_key,'status',s.status,'currentPeriodEnd',s.current_period_end,"
                +"'limits',e.limits_json,'reconciledAt',e.reconciled_at)::text FROM billing.subscription s JOIN billing.entitlement e "
                +"ON e.workspace_id=s.workspace_id AND e.subscription_id=s.id WHERE s.workspace_id=?",(r,n)->read(r.getString(1)),workspaceId)
                .stream().findFirst().orElse(json.createObjectNode().put("status","trial"));}
    private void upsertSubscription(Customer customer,String stripeId,String plan,BillingPolicy.EntitlementState state,Instant period,Instant updated){
        if(stripeId==null)throw invalid("STRIPE_SUBSCRIPTION_MISSING");Subscription current=subscription(customer.workspaceId());String dbState=state.name().toLowerCase();
        if(current==null){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO billing.subscription(workspace_id,id,customer_id,stripe_subscription_id,plan_key,status,"
                +"current_period_end,payment_failed_at,provider_updated_at) VALUES (?,?,?,?,?,?,?,?,?)",customer.workspaceId(),id,customer.id(),stripeId,plan,dbState,
                period,List.of("grace","restricted").contains(dbState)?updated:null,updated);jdbc.update("INSERT INTO billing.entitlement(workspace_id,id,subscription_id,state,"
                +"limits_json,provider_updated_at,reconciled_at) VALUES (?,?,?, ?,?::jsonb,?,?)",customer.workspaceId(),UUID.randomUUID(),id,dbState,limits(plan),updated,clock.instant());}
        else{jdbc.update("UPDATE billing.subscription SET stripe_subscription_id=?,plan_key=?,status=?,current_period_end=?,payment_failed_at=?,provider_updated_at=?,"
                +"version=version+1 WHERE workspace_id=? AND id=?",stripeId,plan,dbState,period,List.of("grace","restricted").contains(dbState)?updated:null,updated,
                customer.workspaceId(),current.id());jdbc.update("UPDATE billing.entitlement SET state=?,limits_json=?::jsonb,provider_updated_at=?,reconciled_at=?,version=version+1 "
                +"WHERE workspace_id=? AND subscription_id=?",dbState,limits(plan),updated,clock.instant(),customer.workspaceId(),current.id());}}
    private BillingPolicy.EntitlementState state(String provider,String eventType){if(eventType.endsWith("deleted"))return BillingPolicy.EntitlementState.CANCELLED;
        if("trialing".equals(provider))return BillingPolicy.EntitlementState.TRIAL;if("active".equals(provider))return BillingPolicy.EntitlementState.ACTIVE;
        if("past_due".equals(provider))return BillingPolicy.EntitlementState.GRACE;return BillingPolicy.EntitlementState.RESTRICTED;}
    private String planForPrice(String price){return prices.entrySet().stream().filter(e->e.getValue().equals(price)).map(Map.Entry::getKey).findFirst().orElse("restricted");}
    private String limits(String plan){return plan.startsWith("studio")?"{\"activeProjects\":100,\"members\":25,\"storageBytes\":1000000000000}":
        "{\"activeProjects\":20,\"members\":5,\"storageBytes\":100000000000}";}
    private Customer customer(UUID workspaceId){return jdbc.query("SELECT workspace_id,id,stripe_customer_id FROM billing.customer WHERE workspace_id=? AND status='active'",
            (r,n)->new Customer(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),workspaceId).stream().findFirst().orElse(null);}
    private Subscription subscription(UUID workspaceId){return jdbc.query("SELECT id,status,provider_updated_at FROM billing.subscription WHERE workspace_id=?",
            (r,n)->new Subscription(r.getObject(1,UUID.class),BillingPolicy.EntitlementState.valueOf(r.getString(2).toUpperCase()),r.getObject(3,Instant.class)),workspaceId)
            .stream().findFirst().orElse(null);}private static String text(JsonNode node,String field){JsonNode value=node.path(field);return value.isTextual()?value.asText():null;}
    private JsonNode read(String value){try{return json.readTree(value);}catch(JacksonException failure){throw invalid("INVALID_STRIPE_EVENT");}}
    private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}}private static ApiFailure invalid(String code){return ApiFailure.of(HttpStatus.BAD_REQUEST,code);}
    private static ApiFailure unavailable(){return ApiFailure.of(HttpStatus.SERVICE_UNAVAILABLE,"BILLING_PROVIDER_UNAVAILABLE");}
    private static ApiFailure notFound(){return ApiFailure.of(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
    private record Customer(UUID workspaceId,UUID id,String stripeId){}private record Subscription(UUID id,BillingPolicy.EntitlementState state,Instant updatedAt){}
}
