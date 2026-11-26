select coalesce(max(version_number), 0) + 1 as version_number from document_versions where document_id = ?;
