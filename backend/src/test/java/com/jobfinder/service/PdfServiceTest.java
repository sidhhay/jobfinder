package com.jobfinder.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfServiceTest {

    private final PdfService pdf = new PdfService();

    @Test
    void removesScriptsAndUnsafeAttributesFromUserHtml() {
        String cleaned = pdf.sanitize("<h1>Hello</h1><script>fetch('https://attacker.invalid')</script>"
                + "<img src=x onerror=alert(1)><a href='javascript:alert(1)'>link</a>");

        assertTrue(cleaned.contains("<h1>Hello</h1>"));
        assertFalse(cleaned.contains("<script"));
        assertFalse(cleaned.contains("onerror"));
        assertFalse(cleaned.contains("javascript:"));
    }

    @Test
    void rejectsBlankAndOversizedDocuments() {
        assertThrows(RuntimeException.class, () -> pdf.sanitize(" "));
        assertThrows(RuntimeException.class, () -> pdf.sanitize("x".repeat(1_000_001)));
    }
}
