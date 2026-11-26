insert into users (user_id, email, password_hash, full_name, raw_job_title, email_verified_at)
values (?, ?, ?, ?, ?, now())
on conflict (user_id) do nothing;
