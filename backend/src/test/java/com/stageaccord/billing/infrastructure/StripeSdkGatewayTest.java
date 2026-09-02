package com.stageaccord.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StripeSdkGatewayTest {
    @Test void malformedWebhookSignatureFailsBeforeProviderReconciliation(){
        var gateway=new StripeSdkGateway("sk_test_dummy_not_for_use","whsec_dummy_not_for_use");
        assertThatThrownBy(()->gateway.verifyAndRetrieve("{}","invalid"))
                .isInstanceOf(StripeSdkGateway.StripeSignatureInvalid.class);
    }
}
