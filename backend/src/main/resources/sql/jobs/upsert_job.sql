insert into jobs (
  source_id,
  title,
  company,
  description,
  location,
  salary_range,
  apply_url,
  source_url,
  external_job_key,
  posted_at,
  is_active,
  stale_reason,
  last_checked_at,
  stale_detected_at
)
values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, null, null, null)
on conflict (external_job_key) where external_job_key is not null
do update set
  source_id   = coalesce(excluded.source_id, jobs.source_id),
  title       = excluded.title,
  company     = excluded.company,
  description = excluded.description,
  location    = excluded.location,
  salary_range = excluded.salary_range,
  apply_url   = excluded.apply_url,
  source_url  = excluded.source_url,
  is_active   = true,
  stale_reason = null,
  last_checked_at = null,
  stale_detected_at = null
returning job_id;
