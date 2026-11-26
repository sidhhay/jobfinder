package com.jobfinder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobfinder.config.AppSettings;
import com.jobfinder.db.Database;
import com.jobfinder.dto.ApplicationDtos.CompileDocumentRequest;
import com.jobfinder.dto.ApplicationDtos.GeneratedDocument;
import com.jobfinder.util.Values;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence and lifecycle of generated documents: draft, compile to PDF, archive. */
@Service
public class DocumentService {

    private static final String PROVIDER = "groq";
    private static final String PROMPT_VERSION = "v1";

    private final Database db;
    private final ApplicationService applications;
    private final DocumentGenerator generator;
    private final PdfService pdf;
    private final StorageService storage;
    private final AppSettings settings;
    private final ObjectMapper mapper;

    public DocumentService(Database db, ApplicationService applications, DocumentGenerator generator,
                           PdfService pdf, StorageService storage, AppSettings settings, ObjectMapper mapper) {
        this.db = db;
        this.applications = applications;
        this.generator = generator;
        this.pdf = pdf;
        this.storage = storage;
        this.settings = settings;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(String userId, String applicationId) {
        applications.require(userId, applicationId);
        return db.fetchAll("documents/list_documents", applicationId);
    }

    /** Creates a new draft and archives the previous current document of the same type. */
    public GeneratedDocument generate(String userId, String applicationId, String type) {
        Map<String, Object> application = applications.require(userId, applicationId);
        boolean isCv = type.equals("cv");
        DocumentGenerator.Draft draft = isCv
                ? generator.buildCv(userId, application)
                : generator.buildCoverLetter(userId, application);
        String title = (isCv ? "CV" : "Cover Letter") + " - "
                + Values.firstNonBlank(Values.str(application.get("company")), "Unknown");
        String documentId = UUID.randomUUID().toString();

        // Stored PDFs are removed before their rows are marked as archived.
        db.fetchAll("documents/get_current_rendered", applicationId, type).forEach(row -> {
            String bucket = Values.str(row.get("rendered_storage_bucket"));
            String path = Values.str(row.get("rendered_storage_path"));
            if (!bucket.isEmpty() && !path.isEmpty()) {
                storage.delete(bucket, path);
            }
        });

        db.inTransaction(() -> {
            db.execute("documents/archive_current", applicationId, type);
            db.fetchOne("documents/insert_generated_document",
                    documentId, userId, application.get("job_id"), applicationId, type, title, draft.html(),
                    "jobful-application-v1", json(draft.profileSnapshot()), json(draft.jobSnapshot()),
                    json(draft.generationParams()), PROVIDER, settings.groqModel(), PROMPT_VERSION);
            db.execute(currentDocumentQuery(type), documentId, applicationId, userId);
            return null;
        });

        return new GeneratedDocument(documentId, applicationId, type, title, draft.html(),
                draft.generationParams(), "draft");
    }

    /** Renders the edited HTML to PDF, uploads it and records a new version. */
    public byte[] compile(String userId, String applicationId, CompileDocumentRequest request) {
        Map<String, Object> document = requireDocument(userId, applicationId, request);
        String safeHtml = pdf.sanitize(request.html());
        byte[] bytes = pdf.render(safeHtml);

        String documentId = request.documentId();
        String bucket = settings.supabaseStorageBucket();
        String path = "users/" + userId + "/applications/" + applicationId + "/" + request.documentType()
                + "/" + documentId + ".pdf";
        String storageError = null;
        try {
            storage.upload(bucket, path, bytes);
        } catch (StorageService.StorageException error) {
            // The download must still work, so the failure is only recorded.
            storageError = error.getMessage();
            bucket = null;
            path = null;
        }
        String storedBucket = bucket;
        String storedPath = path;
        String failure = storageError;

        db.inTransaction(() -> {
            int version = ((Number) db.fetchOne("documents/next_version_number", documentId)
                    .get("version_number")).intValue();
            db.execute("documents/finalize_document",
                    safeHtml, safeHtml, storedBucket, storedPath, "application/pdf", bytes.length,
                    failure, version, documentId, applicationId, userId);
            db.execute("documents/insert_document_version",
                    documentId, version, safeHtml, safeHtml, storedBucket, storedPath,
                    "application/pdf", bytes.length, failure, json(document.get("generation_params")),
                    PROVIDER, settings.groqModel(), PROMPT_VERSION);
            db.execute(currentDocumentQuery(request.documentType()), documentId, applicationId, userId);
            return null;
        });
        return bytes;
    }

    private Map<String, Object> requireDocument(String userId, String applicationId, CompileDocumentRequest request) {
        applications.require(userId, applicationId);
        Map<String, Object> document = null;
        if (isUuid(request.documentId())) {
            document = db.fetchOne("documents/get_document", request.documentId(), applicationId, userId);
        }
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document not found or not yours");
        }
        if (!request.documentType().equals(document.get("document_type"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Document type does not match the request");
        }
        return document;
    }

    private static boolean isUuid(String value) {
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String currentDocumentQuery(String type) {
        return type.equals("cv") ? "documents/set_current_cv" : "documents/set_current_cover_letter";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialise document data", error);
        }
    }
}
