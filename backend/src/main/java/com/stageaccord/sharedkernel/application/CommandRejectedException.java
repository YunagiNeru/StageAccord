package com.stageaccord.sharedkernel.application;

public final class CommandRejectedException extends RuntimeException {
    private final RejectionCode code;

    private CommandRejectedException(RejectionCode code) {
        super(code.name());
        this.code = code;
    }

    public static CommandRejectedException of(RejectionCode code) {
        return new CommandRejectedException(code);
    }

    public RejectionCode code() {
        return code;
    }
}
