package com.jobfinder.service;

import com.jobfinder.db.Database;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Loads user profiles, including the derived onboarding fields. */
@Service
public class UserAccountService {

    public static final String DEV_USER_ID = "00000000-0000-0000-0000-000000000001";
    public static final String DEV_EMAIL = "devroot@jobful.dev";

    private final Database db;

    public UserAccountService(Database db) {
        this.db = db;
    }

    /** @return the profile with onboarding fields, or {@code null} if the user does not exist */
    public Map<String, Object> findProfile(String userId) {
        try {
            UUID.fromString(userId);
        } catch (IllegalArgumentException error) {
            return null;
        }
        Map<String, Object> user = db.fetchOne("auth/get_user_by_id", userId);
        if (user == null) {
            return null;
        }
        int selected = ((Number) user.getOrDefault("selected_tags_count", 0)).intValue();
        Object title = user.get("raw_job_title");
        user.put("selected_tags_count", selected);
        user.put("onboarding_complete", title != null && !String.valueOf(title).isEmpty() && selected > 0);
        return user;
    }

    /** Development bypass: creates the fixed local user if needed and returns its profile. */
    public Map<String, Object> ensureDevUser() {
        db.execute("auth/ensure_dev_user", DEV_USER_ID, DEV_EMAIL, "$dev-bypass$", "Dev Root", "Software Engineer");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("user_id", DEV_USER_ID);
        user.put("email", DEV_EMAIL);
        user.put("full_name", "Dev Root");
        user.put("raw_job_title", "Software Engineer");
        user.put("email_verified_at", "2024-01-01T00:00:00+00:00");
        user.put("cv_uploaded", false);
        user.put("experience_years", -1);
        user.put("selected_tags_count", 1);
        user.put("onboarding_complete", true);
        return user;
    }
}
