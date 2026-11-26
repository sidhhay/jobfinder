# Setup Guide (new laptop)

Follow these steps in order to run the whole project (database, Java backend, web frontend and AI
pipeline) on another computer. The commands are for **Windows 10/11 with PowerShell**; notes for
macOS and Linux are at the end.

You need an internet connection for the first run (downloads of libraries and a browser) and for the
AI and job search features.

---

## 1. Install the required software

| Software | Version | Install command or download |
|---|---|---|
| JDK | 21 | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Node.js (includes npm) | 20 or newer | `winget install OpenJS.NodeJS.LTS` |
| PostgreSQL | 15 or newer | `winget install PostgreSQL.PostgreSQL.17` or <https://www.postgresql.org/download/> |
| Apache Maven | 3.9 or newer | <https://maven.apache.org/download.cgi> (binary zip) |

**Maven:** unzip it, then either add its `bin` folder to the `Path` environment variable, or place it
next to the project as `javakit\maven` (so that `javakit\maven\bin\mvn.cmd` sits beside the
`jobfinder` folder). `run.ps1` finds that location automatically.

**PostgreSQL:** remember the password you choose for the `postgres` user during installation.

Close and reopen PowerShell, then check:

```powershell
java -version     # must show 21
mvn -version      # must show Java 21 as well
node -v
```

If `java` shows an older version, point `JAVA_HOME` to the JDK 21 folder and reopen PowerShell.

## 2. Unzip the project

Unzip to a short path, for example `C:\projects\jobfinder`. Then:

```powershell
cd C:\projects\jobfinder
```

If Windows blocked the downloaded files, unblock them once:

```powershell
Get-ChildItem -Recurse | Unblock-File
```

## 3. Create the configuration files

The zip contains **no passwords or API keys**. Create your own two files:

```powershell
Copy-Item backend\.env.example backend\.env
Copy-Item frontend\.env.local.example frontend\.env.local
notepad backend\.env
```

Change **at least** these lines in `backend\.env`:

```ini
APP_DATABASE_URL=postgresql://postgres:YOUR_PASSWORD@localhost:5432/postgres
JWT_SECRET_KEY=put-a-long-random-text-here-at-least-32-characters
GROQ_API_KEY=your-groq-key
GROQ_MODEL=openai/gpt-oss-120b
```

- Special characters in the database password must be URL encoded: `@` is `%40`, `#` is `%23`,
  `!` is `%21`, `$` is `%24`, a space is `%20`.
- **Change `GROQ_MODEL`.** The value in the example file is an older model that may not be available.
- Add the job search keys, otherwise search returns no jobs.

How to get each key, and every other setting, is described in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md).

`frontend\.env.local` works without changes.

## 4. Create the database tables (only once)

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-db.ps1
```

Enter the PostgreSQL password when asked. The expected last line is
`Database ready in schema 'jobful'.`

The script looks for PostgreSQL 18 first. For another version, if `psql` is not on the `Path`, give
its location:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-db.ps1 -Psql "C:\Program Files\PostgreSQL\17\bin\psql.exe"
```

> **Warning:** `database\schema\setup.sql` deletes and recreates all project tables. The script
> refuses to run if tables already exist. Using `-Force` destroys all stored data.

## 5. Run the project

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

The first run takes several minutes because it:

1. downloads the Java libraries and builds the backend,
2. downloads the Chromium browser used to create PDFs,
3. installs the frontend packages,
4. opens two windows: the backend (port 8000) and the frontend (port 3000).

Keep both windows open. Wait until the frontend window says it is ready, then open
<http://localhost:3000>.

Check the backend at <http://localhost:8000/api/health>. It should answer with `"status":"ok"`.

## 6. Use the application (demo flow)

1. **Register** with name, email and password (at least 8 characters).
2. **Verify the email.** Without email settings, the 6 digit code is shown on the page as "Dev code".
3. **Onboarding:** enter a job title, upload a text based PDF CV (maximum 5 MB), review the parsed
   data, confirm, and select skill tags.
4. **Jobs:** search for a role, then track a job.
5. **Dashboard:** change the application status.
6. **Application workspace:** generate a CV and a cover letter, edit them, compile and download PDF.

Always read generated documents before sending them. AI text can contain mistakes.

## 7. Stop, restart, rebuild

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Stop      # stop both servers
powershell -ExecutionPolicy Bypass -File .\run.ps1            # start again
powershell -ExecutionPolicy Bypass -File .\run.ps1 -Rebuild   # after changing Java code (stop first)
```

Restart the servers after editing `.env` or `.env.local`.

## Running by hand (also for macOS and Linux)

```bash
# once: create the tables in an empty database
psql -d postgres -c "create extension if not exists citext" -c "create extension if not exists pgcrypto" -c "create schema jobful"
PGOPTIONS="-c search_path=jobful,public" psql -d postgres -v ON_ERROR_STOP=1 -f database/schema/setup.sql

# once: configuration (edit backend/.env afterwards)
cp backend/.env.example backend/.env
cp frontend/.env.local.example frontend/.env.local

# terminal 1: backend
cd backend
mvn -DskipTests package
java -cp target/jobfinder-backend.jar -Dloader.main=com.microsoft.playwright.CLI \
     org.springframework.boot.loader.launch.PropertiesLauncher install chromium
java -jar target/jobfinder-backend.jar

# terminal 2: frontend
cd frontend
npm ci
npm run dev
```

On Linux, Chromium may need extra system libraries; run the same Playwright command with
`install --with-deps chromium`. These macOS and Linux steps have not been tested.

Problems? See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
