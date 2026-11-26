insert into project_keywords (project_id, keyword_id)
values (?, ?)
on conflict do nothing;
