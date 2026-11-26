package com.jobfinder.dto;

import java.util.List;
import java.util.Map;

/** Job search bodies. */
public final class JobDtos {

    private JobDtos() {
    }

    /** Each item is a job listing enriched by the filter pipeline. */
    public record SearchResponse(
            List<Map<String, Object>> items, int total, int fetched, List<String> warnings) {
    }

    public record SearchFilters(String country, int salaryMin, String employmentType, String workModel) {
    }
}
