package com.stageaccord.publiccatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.stageaccord.intake.domain.IntakePolicy;
import com.stageaccord.publiccatalog.api.PublicProfileProjection;
import com.stageaccord.publiccatalog.domain.PublicationPolicy;
import com.stageaccord.publiccatalog.domain.WorkflowDefinition;

class PublicCatalogPolicyTest {
    @Test
    void publicProjectionSerializesOnlyExplicitlyAllowedFields() throws Exception {
        var projection = new PublicProfileProjection("dummy-creator", "Dummy Creator", "Summary",
                PublicProfileProjection.IntakeAvailability.OPEN,
                List.of(new PublicProfileProjection.PublishedServiceSummary("dummy-service", "Service", "Summary")));
        String json = new ObjectMapper().writeValueAsString(projection);
        assertThat(json).contains("dummy-creator", "dummy-service")
                .doesNotContain("workspaceId", "accountId", "email", "role", "draft", "internal");
    }

    @Test
    void workflowMustBeLinearCompleteAndPublishable() {
        assertThatThrownBy(() -> new WorkflowDefinition(List.of(
                new WorkflowDefinition.Checkpoint(2, 1, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        var workflow = new WorkflowDefinition(List.of(
                new WorkflowDefinition.Checkpoint(1, 2, 1, 1),
                new WorkflowDefinition.Checkpoint(2, 3, 2, 2)));
        assertThat(workflow.checkpoints()).hasSize(2);
        new PublicationPolicy().requirePublishable(true, true, true, true);
    }

    @Test
    void publicationStopsWhenEntitlementOrFeatureCannotAuthorizeIt() {
        var policy = new PublicationPolicy();
        assertThatThrownBy(() -> policy.requirePublishable(true, true, false, true))
                .isInstanceOf(PublicationPolicy.CatalogRuleViolation.class);
        assertThatThrownBy(() -> policy.requirePublishable(true, true, true, false))
                .isInstanceOf(PublicationPolicy.CatalogRuleViolation.class);
    }

    @Test
    void intakeFailsClosedWhenRateServiceIsUnavailable() {
        var policy = new IntakePolicy();
        var unavailable = new IntakePolicy.Evaluation(false, true, true, true, true, false, true, true);
        assertThatThrownBy(() -> policy.requireAcceptable(unavailable))
                .isInstanceOfSatisfying(IntakePolicy.IntakeRuleViolation.class,
                        error -> assertThat(error.reason()).isEqualTo(IntakePolicy.Reason.RATE_SERVICE_UNAVAILABLE));
        policy.requireAcceptable(new IntakePolicy.Evaluation(true, true, true, true, true, false, true, true));
    }
}
