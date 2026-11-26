update applications
set status = ?, updated_at = now()
where application_id = ? and user_id = ?
returning application_id, status;
