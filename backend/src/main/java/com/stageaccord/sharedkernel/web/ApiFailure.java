package com.stageaccord.sharedkernel.web;

import org.springframework.http.HttpStatus;

public final class ApiFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ApiFailure(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static ApiFailure of(HttpStatus status, String code) {
        if (status.is2xxSuccessful()) throw new IllegalArgumentException("failure status must not be successful");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("failure code is required");
        return new ApiFailure(status, code);
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }
}
