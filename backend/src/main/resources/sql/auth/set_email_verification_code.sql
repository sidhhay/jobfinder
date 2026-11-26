update users
set email_verification_code_hash = ?,
    email_verification_code_salt = ?,
    email_verification_expires_at = ?,
    email_verification_sent_at = now(),
    email_verification_attempts = 0,
    updated_at = now()
where user_id = ?
returning user_id, email, full_name, raw_job_title, bio, email_verified_at, email_verification_sent_at, email_verification_expires_at, email_verification_attempts;
