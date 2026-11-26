select ct.tag_name
from job_tags jt
join canonical_tags ct on ct.tag_id = jt.tag_id
where jt.job_id = ?
order by ct.tag_name;
