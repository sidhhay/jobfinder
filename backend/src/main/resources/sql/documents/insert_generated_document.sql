insert into generated_documents (
  document_id, user_id, job_id, application_id, document_type, title, content, content_format,
  template_name, generation_status, source_profile_snapshot, source_job_snapshot, generation_params,
  provider, model_name, prompt_version, generation_source, is_current, latest_version_number
)
values (?, ?, ?, ?, ?, ?, ?, 'html', ?, 'draft', ?, ?, ?, ?, ?, ?, 'ai', true, 1)
returning document_id;
