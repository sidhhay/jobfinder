package com.jobfinder.controller;

import com.jobfinder.config.AppSettings;
import com.jobfinder.db.Database;
import com.jobfinder.dto.CvDtos.ConfirmRequest;
import com.jobfinder.dto.CvDtos.ConfirmResponse;
import com.jobfinder.dto.CvDtos.Preview;
import com.jobfinder.dto.OnboardingDtos.OnboardingState;
import com.jobfinder.dto.OnboardingDtos.ProfileUpdateRequest;
import com.jobfinder.dto.OnboardingDtos.SelectedTagsRequest;
import com.jobfinder.security.CurrentUser;
import com.jobfinder.security.RateLimit;
import com.jobfinder.service.CvService;
import com.jobfinder.service.OnboardingService;
import com.jobfinder.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Onboarding routes: profile, tags and CV import. */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private static final Set<String> PDF_TYPES = Set.of("application/pdf", "application/x-pdf");
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final OnboardingService onboarding;
    private final CvService cv;
    private final Database db;
    private final AppSettings settings;

    public OnboardingController(OnboardingService onboarding, CvService cv, Database db, AppSettings settings) {
        this.onboarding = onboarding;
        this.cv = cv;
        this.db = db;
        this.settings = settings;
    }

    @GetMapping("/state")
    public OnboardingState state(CurrentUser user) {
        return onboarding.state(user.id());
    }

    @PutMapping("/profile")
    public OnboardingState profile(CurrentUser user, @Valid @RequestBody ProfileUpdateRequest request) {
        return onboarding.updateProfile(user.id(), request);
    }

    @PutMapping("/selected-tags")
    public OnboardingState selectedTags(CurrentUser user, @Valid @RequestBody SelectedTagsRequest request) {
        return onboarding.selectTags(user.id(), request.tagIds());
    }

    @GetMapping("/eligible-jobs")
    public Map<String, Object> eligibleJobs(CurrentUser user) {
        return Map.of("items", db.fetchAll("jobs/get_eligible_jobs", user.id()));
    }

    @GetMapping("/generate-tags")
    public Map<String, Object> generateTags(CurrentUser user) {
        return Map.of("tags", onboarding.tagsFromCv(user.id()));
    }

    /** Parses the CV and returns a preview for review. Nothing is written to the database. */
    @PostMapping("/upload-cv")
    @RateLimit(perMinute = 5)
    public Preview uploadCv(CurrentUser user, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getContentType() == null || !PDF_TYPES.contains(file.getContentType())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF files accepted");
        }
        int cap = settings.cvMaxBytes();
        if (file.getSize() > cap) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "PDF exceeds " + cap / (1024 * 1024) + "MB limit");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (bytes.length < PDF_MAGIC.length
                || !Arrays.equals(bytes, 0, PDF_MAGIC.length, PDF_MAGIC, 0, PDF_MAGIC.length)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File is not a valid PDF");
        }
        return cv.preview(bytes);
    }

    /** Writes the data the user reviewed and confirmed. */
    @PostMapping("/confirm-cv")
    @RateLimit(perMinute = 10)
    public ConfirmResponse confirmCv(CurrentUser user, @RequestBody ConfirmRequest request) {
        return cv.confirm(user.id(), request);
    }
}
