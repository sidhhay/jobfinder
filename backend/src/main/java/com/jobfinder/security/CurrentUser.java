package com.jobfinder.security;

import java.util.Map;

/**
 * The authenticated user. Declaring a controller parameter of this type makes the route
 * require authentication.
 */
public record CurrentUser(Map<String, Object> profile) {

    public String id() {
        return String.valueOf(profile.get("user_id"));
    }
}
