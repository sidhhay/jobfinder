update applications set current_cover_letter_document_id = ?, updated_at = now()
where application_id = ? and user_id = ?;
