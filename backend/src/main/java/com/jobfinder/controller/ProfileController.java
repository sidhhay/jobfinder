package com.jobfinder.controller;

import com.jobfinder.dto.AuthDtos.UserResponse;
import com.jobfinder.dto.OnboardingDtos.AccountUpdateRequest;
import com.jobfinder.security.CurrentUser;
import com.jobfinder.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Account settings and profile data. */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PutMapping("/account")
    public UserResponse account(CurrentUser user, @Valid @RequestBody AccountUpdateRequest request) {
        return profiles.updateAccount(user.id(), request);
    }

    @GetMapping("/cv-data")
    public Map<String, Object> cvData(CurrentUser user) {
        return profiles.cvData(user.id());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(CurrentUser user) {
        return profiles.stats(user.id());
    }
}
