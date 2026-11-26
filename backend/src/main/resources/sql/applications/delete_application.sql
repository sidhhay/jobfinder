delete from applications
where application_id = ? and user_id = ?
returning application_id, job_id;
