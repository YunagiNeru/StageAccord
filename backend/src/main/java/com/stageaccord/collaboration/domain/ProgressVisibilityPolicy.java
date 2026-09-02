package com.stageaccord.collaboration.domain;

public final class ProgressVisibilityPolicy {
    public boolean clientCanRead(Visibility visibility, PublicationStatus status) {
        return visibility == Visibility.CLIENT && status == PublicationStatus.PUBLISHED;
    }

    public Preview preview(String body, Visibility visibility) {
        return new Preview(body, visibility, visibility == Visibility.CLIENT);
    }

    public enum Visibility { PRIVATE, CLIENT }
    public enum PublicationStatus { DRAFT, PUBLISHED, SUPERSEDED }
    public record Preview(String body, Visibility visibility, boolean visibleToClient) {}
}
