insert into refresh_sessions (refresh_session_id, user_id, current_jti, expires_at)
values (?, ?, ?, ?)
returning refresh_session_id, user_id, current_jti, expires_at, revoked_at, revoke_reason, rotated_at, last_seen_at, created_at;
