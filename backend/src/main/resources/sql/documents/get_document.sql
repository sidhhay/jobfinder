select document_id, application_id, user_id, document_type, title, content, generation_params, latest_version_number
from generated_documents
where document_id = ? and application_id = ? and user_id = ?
limit 1;
