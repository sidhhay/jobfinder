package com.jobfinder.controller;

import com.jobfinder.dto.ApplicationDtos.CompileDocumentRequest;
import com.jobfinder.dto.ApplicationDtos.GenerateDocumentRequest;
import com.jobfinder.dto.ApplicationDtos.GeneratedDocument;
import com.jobfinder.dto.ApplicationDtos.Overview;
import com.jobfinder.dto.ApplicationDtos.PrepareApplyResponse;
import com.jobfinder.dto.ApplicationDtos.RemoveResponse;
import com.jobfinder.dto.ApplicationDtos.StatusResponse;
import com.jobfinder.dto.ApplicationDtos.TrackJobRequest;
import com.jobfinder.dto.ApplicationDtos.UpdateStatusRequest;
import com.jobfinder.security.CurrentUser;
import com.jobfinder.service.ApplicationService;
import com.jobfinder.service.DocumentService;
import com.jobfinder.util.Values;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Application tracking and document routes. */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applications;
    private final DocumentService documents;

    public ApplicationController(ApplicationService applications, DocumentService documents) {
        this.applications = applications;
        this.documents = documents;
    }

    @GetMapping
    public Map<String, Object> list(CurrentUser user) {
        return Map.of("items", applications.list(user.id()));
    }

    @GetMapping("/overview")
    public Overview overview(CurrentUser user) {
        return applications.overview(user.id());
    }

    @GetMapping("/{applicationId}")
    public Map<String, Object> detail(CurrentUser user, @PathVariable String applicationId) {
        return applications.detail(user.id(), applicationId);
    }

    @PostMapping("/track")
    public StatusResponse track(CurrentUser user, @Valid @RequestBody TrackJobRequest request) {
        return applications.track(user.id(), request);
    }

    @PutMapping("/{applicationId}/status")
    public StatusResponse status(CurrentUser user, @PathVariable String applicationId,
                                 @Valid @RequestBody UpdateStatusRequest request) {
        return applications.updateStatus(user.id(), applicationId, request.status());
    }

    @PostMapping("/{applicationId}/prepare-apply")
    public PrepareApplyResponse prepareApply(CurrentUser user, @PathVariable String applicationId) {
        return applications.prepareApply(user.id(), applicationId);
    }

    @DeleteMapping("/{applicationId}")
    public RemoveResponse remove(CurrentUser user, @PathVariable String applicationId) {
        return applications.removeStale(user.id(), applicationId);
    }

    @GetMapping("/{applicationId}/documents")
    public Map<String, Object> listDocuments(CurrentUser user, @PathVariable String applicationId) {
        return Map.of("items", documents.list(user.id(), applicationId));
    }

    @PostMapping("/{applicationId}/documents/generate")
    public GeneratedDocument generate(CurrentUser user, @PathVariable String applicationId,
                                      @Valid @RequestBody GenerateDocumentRequest request) {
        return documents.generate(user.id(), applicationId, request.documentType());
    }

    @PostMapping("/{applicationId}/documents/compile")
    public ResponseEntity<byte[]> compile(CurrentUser user, @PathVariable String applicationId,
                                          @Valid @RequestBody CompileDocumentRequest request) {
        byte[] pdf = documents.compile(user.id(), applicationId, request);
        String filename = request.documentType().replace('_', '-') + "-"
                + Values.truncate(request.documentId(), 8) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(pdf);
    }
}
