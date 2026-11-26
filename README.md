# Jobfinder

Job application system: profile and CV import, live job search, application tracking, and tailored
CV and cover letter generation.

- **Backend:** Java 21, Spring Boot 3.5, JDBC with raw SQL (no ORM), PostgreSQL
- **Frontend:** Next.js (React, TypeScript, Tailwind)
- **Libraries:** PDFBox (CV text), Pebble (HTML templates), Playwright (HTML to PDF)

## Documentation

| File | Contents |
|---|---|
| [SETUP.md](SETUP.md) | **Start here:** install and run on a new laptop, step by step |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Every setting and where to get API keys |
| [docs/AI_PIPELINE.md](docs/AI_PIPELINE.md) | How CV parsing, job filtering and document generation work |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common errors and fixes |
| [database/README.md](database/README.md) | Database notes (hosted Supabase variant) |

## Run

If PowerShell blocks scripts, use `powershell -ExecutionPolicy Bypass -File .\run.ps1`.

```powershell
.\run.ps1            # build if needed, then start backend (8000) and frontend (3000)
.\run.ps1 -Rebuild   # after changing Java code
.\run.ps1 -Stop      # stop both
```

Open <http://localhost:3000>. Health check: <http://localhost:8000/api/health>.

## First time setup (summary, details in [SETUP.md](SETUP.md))

1. Install JDK 21, Maven 3.9+, Node 20+ and PostgreSQL. `run.ps1` also accepts a portable
   toolchain in a sibling folder `..\javakit\jdk` and `..\javakit\maven`.
2. Copy `backend\.env.example` to `backend\.env` and fill it in. Local database example:
   `APP_DATABASE_URL=postgresql://postgres:<url-encoded-password>@localhost:5432/postgres`
3. Copy `frontend\.env.local.example` to `frontend\.env.local`.
4. Create the tables once: `.\scripts\init-db.ps1`
5. Run `.\run.ps1`.

`backend\.env` and `frontend\.env.local` contain secrets. **Remove them before sharing or submitting
this folder.**

| Setting | Needed for |
|---|---|
| `APP_DATABASE_URL` | everything |
| `JWT_SECRET_KEY` | login |
| `GROQ_API_KEY` | CV parsing, AI job screening, document writing |
| `RAPIDAPI_KEY`, `ADZUNA_APP_ID`, `ADZUNA_APP_KEY` | job search |
| `SMTP_*` | verification emails (required outside local development) |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | storing generated PDFs (download works without it) |
| `DEV_AUTO_LOGIN=true` + `NEXT_PUBLIC_DEV_AUTO_LOGIN=true` | skip login during local development |

## Package structure

```
backend/src/main/java/com/jobfinder/
  config/       settings, data source, CORS and MVC setup
  controller/   REST routes
  dto/          request and response records
  service/      business logic
  db/           JDBC access, SQL file loading, SQL log
  security/     JWT, password hashing, current user, rate limiting
  client/       outbound HTTP and AI clients
  web/          exception handling, response filter
  ats/          command line scanner for company job boards
  util/         small helpers
backend/src/main/resources/
  sql/          one file per query
  templates/    CV and cover letter templates
database/       schema, seed data, views
frontend/       web client
```

## API

| Method | Path |
|---|---|
| GET | `/api/health` |
| POST | `/api/auth/register`, `/verify-email`, `/resend-verification`, `/login`, `/refresh`, `/logout`, `/session` |
| GET | `/api/auth/me`, `/dev-session`, `/google/start`, `/google/callback` |
| GET | `/api/onboarding/state`, `/generate-tags`, `/eligible-jobs` |
| PUT | `/api/onboarding/profile`, `/selected-tags` |
| POST | `/api/onboarding/upload-cv`, `/confirm-cv` |
| PUT | `/api/profile/account` |
| GET | `/api/profile/cv-data`, `/stats` |
| GET | `/api/jobs/search`, `/eligible` |
| GET | `/api/applications`, `/overview`, `/{id}`, `/{id}/documents` |
| POST | `/api/applications/track`, `/{id}/prepare-apply`, `/{id}/documents/generate`, `/{id}/documents/compile` |
| PUT | `/api/applications/{id}/status` |
| DELETE | `/api/applications/{id}` |

## Tests and tools

```powershell
cd backend
mvn test
mvn -q compile exec:java "-Dexec.mainClass=com.jobfinder.ats.AtsScanner" "-Dexec.args=--limit 3"
```

## Security notes

- The backend listens on `127.0.0.1` only (override with `HOST`).
- `DEV_AUTO_LOGIN` disables authentication; keep it off outside local development.
- SQL query logging is disabled by default. Set `SQL_LOG_ENABLED=true` only for controlled local debugging; never expose SQL logs in production.
- Verification codes are never returned just because SMTP is missing. Configure SMTP for email verification; code exposure requires the explicit `AUTH_DEBUG_RETURN_CODES=true` development setting and must remain disabled in production.
- Authentication rate limits, verification cooldowns and OAuth state are held in memory. For multi-instance deployments, move these controls to shared storage such as Redis.
- Google OAuth state is bound to a short-lived HttpOnly browser cookie; OAuth credentials are returned in the login URL fragment and removed by the frontend callback handler.
