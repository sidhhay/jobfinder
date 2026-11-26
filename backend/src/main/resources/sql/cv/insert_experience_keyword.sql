insert into experience_keywords (experience_id, keyword_id)
values (?, ?)
on conflict do nothing;
