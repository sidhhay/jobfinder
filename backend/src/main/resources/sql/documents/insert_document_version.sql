insert into document_versions (
  document_id, version_number, content, content_format, rendered_content, rendered_format,
  rendered_storage_bucket, rendered_storage_path, rendered_mime_type, rendered_file_size_bytes,
  generation_error, generation_params, provider, model_name, prompt_version
) values (?, ?, ?, 'html', ?, 'pdf', ?, ?, ?, ?, ?, ?, ?, ?, ?);
