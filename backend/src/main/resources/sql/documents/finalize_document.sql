update generated_documents
set content = ?,
    content_format = 'html',
    rendered_content = ?,
    rendered_format = 'pdf',
    rendered_storage_bucket = ?,
    rendered_storage_path = ?,
    rendered_mime_type = ?,
    rendered_file_size_bytes = ?,
    generation_error = ?,
    generation_status = 'ready',
    is_current = true,
    latest_version_number = ?,
    updated_at = now()
where document_id = ? and application_id = ? and user_id = ?;
