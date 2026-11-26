package com.jobfinder.service;

import com.jobfinder.db.Database;
import com.jobfinder.dto.OnboardingDtos.OnboardingState;
import com.jobfinder.dto.OnboardingDtos.ProfileUpdateRequest;
import com.jobfinder.dto.OnboardingDtos.Tag;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Profile setup and tag selection. */
@Service
public class OnboardingService {

    private final Database db;
    private final TagRecommender recommender;

    public OnboardingService(Database db, TagRecommender recommender) {
        this.db = db;
        this.recommender = recommender;
    }

    public OnboardingState state(String userId) {
        Map<String, Object> user = db.fetchOne("auth/get_user_by_id", userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }
        String title = Values.str(user.get("raw_job_title"));
        String bio = (String) user.get("bio");

        List<Tag> canonical = tags("onboarding/get_canonical_tags");
        Set<String> names = recommender.recommend(title, bio, canonical.stream().map(Tag::tagName).toList());
        List<Tag> recommended = canonical.stream().filter(tag -> names.contains(tag.tagName())).toList();

        List<Tag> assigned = tags("onboarding/get_user_assigned_tags", userId);
        if (assigned.isEmpty()) {
            // Fresh users get every canonical tag so that any of them can be selected.
            assignAll(userId);
            assigned = tags("onboarding/get_user_assigned_tags", userId);
        }
        List<Tag> selected = tags("onboarding/get_user_selected_tags", userId);
        return new OnboardingState(Values.str(user.get("user_id")), title, bio, recommended, assigned, selected);
    }

    public OnboardingState updateProfile(String userId, ProfileUpdateRequest request) {
        db.inTransaction(() -> {
            if (db.fetchOne("onboarding/update_user_profile",
                    request.rawJobTitle(), request.bio(), userId) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
            }
            db.execute("onboarding/clear_user_assigned_tags", userId);
            db.execute("onboarding/clear_user_selected_tags", userId);
            assignAll(userId);
            return null;
        });
        return state(userId);
    }

    public OnboardingState selectTags(String userId, List<UUID> tagIds) {
        Set<UUID> unique = new LinkedHashSet<>(tagIds);
        if (unique.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select at least one tag");
        }
        db.inTransaction(() -> {
            assignAll(userId);
            db.execute("onboarding/clear_user_selected_tags", userId);
            unique.forEach(id -> db.execute("onboarding/insert_user_selected_tag", userId, id));
            return null;
        });
        return state(userId);
    }

    /** Distinct tags found on the user's education, experiences and projects. */
    public List<Tag> tagsFromCv(String userId) {
        return tags("onboarding/get_user_cv_tags", userId, userId, userId);
    }

    private void assignAll(String userId) {
        for (Tag tag : tags("onboarding/get_canonical_tags")) {
            db.execute("onboarding/insert_user_assigned_tag", userId, tag.tagId());
        }
    }

    private List<Tag> tags(String query, Object... params) {
        return db.fetchAll(query, params).stream()
                .map(row -> new Tag(Values.str(row.get("tag_id")), Values.str(row.get("tag_name"))))
                .toList();
    }
}
