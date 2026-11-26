package com.jobfinder.web;

import org.springframework.http.HttpStatus;

/** Error that is returned to the client as {@code {"detail": "..."}} with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public ApiException(HttpStatus status, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
