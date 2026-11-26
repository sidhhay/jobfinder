package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.AuthDtos.UserResponse;
import com.jobfinder.dto.OnboardingDtos.AccountUpdateRequest;
import com.jobfinder.security.PasswordHasher;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Account settings, stored CV data and application statistics. */
@Service
public class ProfileService {

    private final Database db;
    private final PasswordHasher passwords;
    private final UserAccountService users;

    public ProfileService(Database db, PasswordHasher passwords, UserAccountService users) {
        this.db = db;
        this.passwords = passwords;
        this.users = users;
    }

    public UserResponse updateAccount(String userId, AccountUpdateRequest request) {
        List<Object> params = new ArrayList<>(Arrays.asList(
                request.fullName(), Objects.requireNonNullElse(request.rawJobTitle(), ""), request.bio(),
                request.phoneNumber(), request.githubUrl(), request.linkedinUrl(), request.notionUrl()));
        String query = "auth/update_user_full_name";
        if (!Values.blank(request.password())) {
            query = "auth/update_user_full_name_password";
            params.add(passwords.hash(request.password()));
        }
        params.add(userId);
        db.fetchOne(query, params.toArray());

        Map<String, Object> profile = users.findProfile(userId);
        if (profile == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        return UserResponse.from(profile);
    }

    public Map<String, Object> cvData(String userId) {
        return Map.of(
                "education", db.fetchAll("cv/get_user_education", userId),
                "experiences", db.fetchAll("cv/get_user_experiences_detail", userId),
                "projects", db.fetchAll("cv/get_user_projects_detail", userId));
    }

    public Map<String, Object> stats(String userId) {
        return Map.of("tag_stats", db.fetchAll("applications/get_applications_per_tag", userId));
    }
}
