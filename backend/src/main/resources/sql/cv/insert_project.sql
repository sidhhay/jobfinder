insert into user_projects (user_id, name, description, github_url, demo_url, tag_id)
values (?, ?, ?, ?, ?, ?)
returning project_id;
