package com.jobfinder.service;

import com.jobfinder.dto.CvDtos.Contact;
import com.jobfinder.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads plain text and contact details from an uploaded CV. The PDF is never written to disk. */
@Component
public class CvTextExtractor {

    private static final Pattern EMAIL =
            Pattern.compile("\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d().\\-\\s]{8,}\\d");
    private static final Pattern LINKEDIN = url("linkedin\\.com");
    private static final Pattern GITHUB = url("github\\.com");
    private static final Pattern NOTION = url("(?:notion\\.so|notion\\.site)");

    private static Pattern url(String host) {
        return Pattern.compile("(?i)\\b(?:https?://)?(?:www\\.)?" + host + "/[^\\s<>()]+");
    }

    public String extractText(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).strip();
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read PDF file", error);
        }
    }

    public Contact contactDetails(String text) {
        Matcher email = EMAIL.matcher(text);
        return new Contact(email.find() ? email.group().strip() : null, phone(text),
                firstUrl(GITHUB, text), firstUrl(LINKEDIN, text), firstUrl(NOTION, text));
    }

    private static String phone(String text) {
        Matcher matcher = PHONE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group().strip();
            int digits = candidate.replaceAll("\\D", "").length();
            if (digits >= 10 && digits <= 15) {
                return candidate.replaceAll("\\s+", " ");
            }
        }
        return null;
    }

    private static String firstUrl(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group().replaceAll("[.,;:)\\]}>\"']+$", "");
        if (value.isEmpty()) {
            return null;
        }
        return value.matches("(?i)^https?://.*") ? value : "https://" + value;
    }
}
