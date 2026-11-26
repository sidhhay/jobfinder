update applications set current_cv_document_id = ?, updated_at = now()
where application_id = ? and user_id = ?;
