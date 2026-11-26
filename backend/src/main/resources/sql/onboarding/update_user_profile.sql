update users
set raw_job_title = ?,
    bio = ?,
    updated_at = now()
where user_id = ?
returning user_id, email, full_name, raw_job_title, bio, phone_number, github_url, linkedin_url, notion_url, created_at, updated_at;
