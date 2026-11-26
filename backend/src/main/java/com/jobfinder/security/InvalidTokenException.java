package com.jobfinder.security;

/** Raised when a token is malformed, forged, expired or of the wrong kind. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
