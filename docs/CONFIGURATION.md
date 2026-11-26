# Configuration

Backend settings live in `backend/.env`. An environment variable with the same name overrides the
file. Restart the backend after every change. Never share this file.

## Required

| Setting | Meaning |
|---|---|
| `APP_DATABASE_URL` | `postgresql://user:password@host:port/database` (password URL encoded) |
| `JWT_SECRET_KEY` | Long random text (32+ characters) that signs login tokens |

## AI and job search

| Setting | Meaning | Where to get it |
|---|---|---|
| `GROQ_API_KEY` | CV parsing, job screening, CV and cover letter writing | <https://console.groq.com/keys> |
| `GROQ_MODEL` | Use `openai/gpt-oss-120b` | Model list in the Groq console |
| `RAPIDAPI_KEY` | JSearch job results | <https://rapidapi.com>, subscribe to the "JSearch" API |
| `ADZUNA_APP_ID`, `ADZUNA_APP_KEY` | Adzuna job results | <https://developer.adzuna.com> |
| `HF_TOKEN` | Optional second AI provider for job screening | <https://huggingface.co/settings/tokens> |
| `JOB_SEARCH_PAGES` | Result pages fetched per provider (default 2); lower saves quota | |
| `JOB_SEARCH_DELAY` | Pause between pages in seconds (default 0.5) | |

Free plans have request limits.

## Optional

| Setting | Meaning |
|---|---|
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM_EMAIL`, `SMTP_USE_TLS` | Send verification codes by email. For Gmail use an app password. If `SMTP_HOST` is empty, the code is shown on the register page. |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_STORAGE_BUCKET` | Store generated PDFs in Supabase Storage (bucket default `generated-documents`). Without these, PDF download still works and the upload failure is only recorded. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` | Google login. Also set `NEXT_PUBLIC_ENABLE_GOOGLE_SSO=true` in the frontend. Not tested in the Java version. |
| `DB_SCHEMA` | Database schema with the tables (default `jobful`) |
| `ALLOWED_ORIGINS` | Web addresses allowed to call the API (default `http://localhost:3000`) |
| `FRONTEND_BASE_URL` | Address used for Google login redirects |
| `PORT`, `HOST` | Backend port (8000) and listen address (127.0.0.1) |
| `ACCESS_TOKEN_MINUTES`, `REFRESH_TOKEN_DAYS` | Login token lifetimes |
| `JWT_KEYS_JSON`, `JWT_ACTIVE_KID` | Optional signing key rotation |
| `CV_MAX_BYTES`, `CV_MIN_TEXT_CHARS` | CV upload limits |

## Skip login during development

Set both values, then restart:

```ini
# backend/.env
DEV_AUTO_LOGIN=true
# frontend/.env.local
NEXT_PUBLIC_DEV_AUTO_LOGIN=true
```

This turns authentication off. Use it only on your own computer.

## Frontend (`frontend/.env.local`)

| Setting | Default |
|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8000/api` |
| `NEXT_PUBLIC_ENABLE_GOOGLE_SSO` | `false` |
| `NEXT_PUBLIC_DEV_AUTO_LOGIN` | not set (off) |
