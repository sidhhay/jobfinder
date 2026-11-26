select rendered_storage_bucket, rendered_storage_path
from generated_documents
where application_id = ?
  and document_type = ?
  and is_current = true
  and rendered_storage_path is not null;
