insert into job_sources (source_name, source_type)
values (?, 'api')
on conflict (source_name) do update set source_name = excluded.source_name
returning source_id;
