with ins as (
  insert into keywords (keyword)
  values (?)
  on conflict (keyword) do nothing
  returning keyword_id
)
select keyword_id from ins
union all
select keyword_id from keywords where keyword = ?
limit 1;
