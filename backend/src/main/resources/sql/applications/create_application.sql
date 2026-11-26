insert into applications (user_id, job_id, status)
values (?, ?, ?)
on conflict (user_id, job_id) do update set status = excluded.status, updated_at = now()
returning application_id, status;
