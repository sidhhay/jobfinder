insert into user_selected_tags (user_id, tag_id)
values (?, ?)
on conflict do nothing;
