update users
set full_name = ?,
    raw_job_title = ?,
    bio = ?,
    phone_number = ?,
    github_url = ?,
    linkedin_url = ?,
    notion_url = ?,
    password_hash = ?,
    updated_at = now()
where user_id = ?
returning user_id;
