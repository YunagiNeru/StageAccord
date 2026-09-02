package com.stageaccord.sharedkernel.application;

public enum CommandClass {
    PUBLIC_QUERY(false),
    PRIVATE_QUERY(false),
    GENERAL_COMMAND(true),
    AUTH_EXCHANGE(true),
    TIME_SENSITIVE_COMMAND(true),
    FILE_COMMAND(true),
    ADMIN_COMMAND(true);

    private final boolean write;

    CommandClass(boolean write) {
        this.write = write;
    }

    public boolean isWrite() {
        return write;
    }
}
