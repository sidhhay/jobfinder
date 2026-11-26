package com.jobfinder.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScreeningRulesTest {

    @Test
    void readsMinimumYearsFromJobText() {
        assertEquals(14, JobFilterService.minimumYears("14-17 years of experience required"));
        assertEquals(5, JobFilterService.minimumYears("5+ years experience"));
        assertEquals(10, JobFilterService.minimumYears("minimum 10 years in industry"));
        assertNull(JobFilterService.minimumYears("freshers welcome"));
    }

    @Test
    void preservesZeroYearsAsKnownExperience() {
        assertEquals(0, JobFilterService.experienceYearsOrUnknown(0));
        assertEquals(2, JobFilterService.experienceYearsOrUnknown(2));
        assertEquals(-1, JobFilterService.experienceYearsOrUnknown(null));
    }

    @Test
    void measuresSkillOverlap() {
        assertEquals(0.5, JobFilterService.skillOverlap("java developer", List.of("java", "react")));
        assertEquals(0.0, JobFilterService.skillOverlap("java developer", List.of()));
    }

    @Test
    void selectsMatchingRecordsOrFallsBackToOne() {
        Map<String, Object> matching = Map.of("tag", "backend", "keywords", List.of("java"));
        Map<String, Object> other = Map.of("tag", "photography", "keywords", List.of("lightroom"));
        List<Map<String, Object>> items = List.of(other, matching);

        assertEquals(List.of(matching), CvContent.select(items, Set.of("backend"), Set.of("java"), 3));
        assertEquals(1, CvContent.select(items, Set.of("robotics"), Set.of("ros"), 3).size());
    }

    @Test
    void formatsCvDates() {
        assertEquals("Jun 2025 -- Present", CvContent.dateRange("2025-06-01", null));
    }
}
