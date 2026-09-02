package com.stageaccord.publiccatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class CatalogIntakeServiceTest {
    @Test
    void publicProjectionUsesAnExplicitAllowList() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var draft = json.readTree("""
                {"displayName":"Studio","bio":"About","categories":["audio"],
                 "externalLinks":["https://example.com"],"intakeStatus":"open",
                 "capacityGuide":"2 slots","accountId":"secret","email":"secret@example.com",
                 "role":"owner","internalStatus":"review"}
                """);

        var projection = CatalogIntakeService.publicProjection(json, draft);

        assertThat(projection.propertyNames()).containsExactlyInAnyOrder(
                "displayName", "bio", "categories", "externalLinks", "intakeStatus", "capacityGuide");
        assertThat(projection.has("accountId")).isFalse();
        assertThat(projection.has("email")).isFalse();
        assertThat(projection.has("role")).isFalse();
        assertThat(projection.has("internalStatus")).isFalse();
    }

    @Test
    void publishedServiceProjectionDropsInternalFields() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var stored = json.readTree("""
                {"title":"Mixing","summary":"Two revisions","deliverables":["wav"],
                 "pricing":{"amount":100},"leadTime":7,"requiredMaterials":[],
                 "usageTerms":"personal","workflow":["review"],
                 "workspaceId":"secret","internalStatus":"draft","ownerEmail":"secret@example.com"}
                """);

        var projection = CatalogIntakeService.publicServiceProjection(json, stored);

        assertThat(projection.propertyNames()).containsExactlyInAnyOrder(
                "title", "summary", "deliverables", "pricing", "leadTime", "requiredMaterials",
                "usageTerms", "workflow");
        assertThat(projection.has("workspaceId")).isFalse();
        assertThat(projection.has("internalStatus")).isFalse();
        assertThat(projection.has("ownerEmail")).isFalse();
    }
}
