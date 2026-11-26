insert into user_assigned_tags (user_id, tag_id)
values (?, ?)
on conflict do nothing;
