select
  document_id, application_id, document_type, title, generation_status, is_current, content_format, content,
  rendered_format, rendered_storage_bucket, rendered_storage_path, rendered_mime_type, rendered_file_size_bytes,
  generation_params, template_name, provider, model_name, prompt_version, created_at, updated_at
from generated_documents
where application_id = ?
order by created_at desc;
