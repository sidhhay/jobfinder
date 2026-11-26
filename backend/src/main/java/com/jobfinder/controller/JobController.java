package com.jobfinder.controller;

import com.jobfinder.db.Database;
import com.jobfinder.dto.JobDtos.SearchFilters;
import com.jobfinder.dto.JobDtos.SearchResponse;
import com.jobfinder.security.CurrentUser;
import com.jobfinder.security.RateLimit;
import com.jobfinder.service.JobFilterService;
import com.jobfinder.service.JobSearchService;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Job listing and live search routes. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    /** Fields of a search result that are returned to the client. */
    private static final List<String> RESULT_FIELDS = List.of(
            "source", "external_id", "title", "company", "location", "country", "description", "apply_url",
            "source_url", "salary_range", "posted_at", "employment_type", "work_model", "brief_description",
            "tech_stack", "why_fit", "matching_experiences", "matching_projects", "gate_provider");

    private final Database db;
    private final JobSearchService search;
    private final JobFilterService filter;

    public JobController(Database db, JobSearchService search, JobFilterService filter) {
        this.db = db;
        this.search = search;
        this.filter = filter;
    }

    @GetMapping("/eligible")
    public Map<String, Object> eligible(CurrentUser user) {
        return Map.of("items", db.fetchAll("jobs/get_eligible_jobs", user.id()));
    }

    @GetMapping("/search")
    @RateLimit(perMinute = 30)
    public SearchResponse search(
            CurrentUser user,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "") String location,
            @RequestParam(defaultValue = "in") String country,
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(name = "employment_type", defaultValue = "") String employmentType,
            @RequestParam(name = "work_model", defaultValue = "") String workModel,
            @RequestParam(name = "date_posted", defaultValue = "month") String datePosted,
            @RequestParam(name = "salary_min", defaultValue = "0") int salaryMin,
            @RequestParam(name = "allow_unspecified_pay", defaultValue = "true") boolean allowUnspecifiedPay) {
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Search query must not be empty");
        }
        JobSearchService.Outcome outcome = search.search(new JobSearchService.Query(query, location, country,
                source, employmentType, workModel, datePosted, salaryMin, allowUnspecifiedPay));
        List<Map<String, Object>> found = outcome.jobs();
        List<Map<String, Object>> items = filter
                .filter(user.id(), found, new SearchFilters(country, salaryMin, employmentType, workModel))
                .stream()
                .map(job -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    RESULT_FIELDS.forEach(field -> view.put(field, job.get(field)));
                    return view;
                })
                .toList();
        return new SearchResponse(items, items.size(), found.size(), outcome.warnings());
    }
}
