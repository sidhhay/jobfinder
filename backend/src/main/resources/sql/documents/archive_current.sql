update generated_documents
set is_current = false,
    generation_status = 'archived',
    updated_at = now()
where application_id = ?
  and document_type = ?
  and is_current = true;
