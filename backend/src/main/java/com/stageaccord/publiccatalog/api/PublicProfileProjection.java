package com.stageaccord.publiccatalog.api;

import java.util.List;
import java.util.Objects;

public record PublicProfileProjection(String slug, String displayName, String summary,
        IntakeAvailability intake, List<PublishedServiceSummary> services) {
    public PublicProfileProjection {
        slug = requireText(slug, "slug");
        displayName = requireText(displayName, "displayName");
        summary = Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(intake, "intake");
        services = List.copyOf(services);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum IntakeAvailability { OPEN, PAUSED, CLOSED }

    public record PublishedServiceSummary(String slug, String name, String summary) {
        public PublishedServiceSummary {
            requireText(slug, "slug");
            requireText(name, "name");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
