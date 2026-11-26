update users
set full_name = ?,
    raw_job_title = ?,
    bio = ?,
    phone_number = ?,
    github_url = ?,
    linkedin_url = ?,
    notion_url = ?,
    updated_at = now()
where user_id = ?
returning user_id;
