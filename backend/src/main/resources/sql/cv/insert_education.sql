insert into user_education (
  user_id, institution, degree, degree_level, field_of_study,
  start_date, end_date, grade, description, tag_id
)
values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
returning education_id;
