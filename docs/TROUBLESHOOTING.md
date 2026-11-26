# Troubleshooting

Read the error in the backend or frontend window first.

| Problem | Solution |
|---|---|
| `running scripts is disabled on this system` | Start scripts with `powershell -ExecutionPolicy Bypass -File .\run.ps1` |
| `java` or `mvn` is not recognised | Install them, add to `Path`, reopen PowerShell (see [SETUP.md](../SETUP.md)) |
| `release version 21 not supported` | Maven uses an old JDK. Set `JAVA_HOME` to the JDK 21 folder |
| Maven cannot download dependencies | Check internet connection or proxy, then run `.\run.ps1 -Rebuild` |
| `Port 8000 is already in use` | `.\run.ps1 -Stop`, or close the other program |
| `backend\.env is missing` | Create it from `backend\.env.example` |
| `APP_DATABASE_URL or DATABASE_URL is not configured` | Fill in `APP_DATABASE_URL` |
| "Database connection is not available" | Start the PostgreSQL service; check user, password (URL encoded), port |
| `relation "users" does not exist` | Run `scripts\init-db.ps1`; check `DB_SCHEMA` |
| init script cannot find `psql` | Pass `-Psql "C:\Program Files\PostgreSQL\<version>\bin\psql.exe"` |
| Browser shows "Could not reach backend" | Check <http://localhost:8000/api/health>; open the app as `http://localhost:3000` |
| Job search is empty | Add RapidAPI / Adzuna keys; try another role or country |
| "Search API error 403" or "429" | Key not subscribed to the API, or quota used up |
| "GROQ_API_KEY not configured" / "Groq call failed" | Check `GROQ_API_KEY` and `GROQ_MODEL` |
| CV upload says it is a scanned image | Use a PDF with selectable text |
| PDF compile fails | Install Chromium again (command below) and restart |
| No verification email | Check `SMTP_*`, or leave `SMTP_HOST` empty to see the code on screen |
| Changes in `.env` have no effect | Stop and start the servers |
| `npm ci` fails | Check `node -v` (20+), delete `frontend\node_modules`, run again |

## Install the PDF browser again

```powershell
cd backend
java -cp target\jobfinder-backend.jar "-Dloader.main=com.microsoft.playwright.CLI" org.springframework.boot.loader.launch.PropertiesLauncher install chromium
```

If it reports an active lock file, make sure no other install is running, delete
`%LOCALAPPDATA%\ms-playwright\__dirlock` and repeat.
