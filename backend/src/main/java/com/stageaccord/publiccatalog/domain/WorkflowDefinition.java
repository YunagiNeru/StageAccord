package com.stageaccord.publiccatalog.domain;

import java.util.List;

public record WorkflowDefinition(List<Checkpoint> checkpoints) {
    public WorkflowDefinition {
        checkpoints = List.copyOf(checkpoints);
        if (checkpoints.isEmpty()) throw new IllegalArgumentException("workflow requires checkpoints");
        for (int index = 0; index < checkpoints.size(); index++) {
            Checkpoint item = checkpoints.get(index);
            if (item.sequence() != index + 1) throw new IllegalArgumentException("checkpoint sequence must be contiguous");
            if (item.requiredItems() < 1) throw new IllegalArgumentException("checkpoint requires at least one item");
        }
    }

    public record Checkpoint(int sequence, int creatorDays, int clientDays, int requiredItems) {
        public Checkpoint {
            if (sequence < 1 || creatorDays < 0 || clientDays < 0 || requiredItems < 0) {
                throw new IllegalArgumentException("invalid checkpoint values");
            }
        }
    }
}
