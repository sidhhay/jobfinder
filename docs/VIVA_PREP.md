# Jobful — Java Lab Eval Prep (30 min)

**Read in this order:** §1 pitch → §3A OOP (4 pillars) → §3B fundamentals → §6 cross-questions → §2 architecture. Skim the rest.

---

## 1. 60-second pitch (say this first)

> "Jobful is a job-search assistant. A student uploads their CV as a PDF. The **Spring Boot 3.5 / Java 21** backend extracts the text with **Apache PDFBox** and sends it to an LLM (**Groq**), which returns structured JSON: education, experience, projects and skill keywords. The student reviews that data and saves it to **PostgreSQL**.
> Then they pick a tag like *backend*. The backend searches several live job APIs, removes duplicates, and runs a **two-tier filter**. Tier 0 is free, rule-based Java: job age, region restrictions, required years of experience, and skill overlap. Tier 1 sends only the unclear jobs to the AI, in parallel using `CompletableFuture`.
> The student tracks jobs through a status pipeline (interested → applying → applied → response → placed). The app can generate a tailored CV and cover letter (Pebble HTML templates) and export them to PDF with headless Chromium (Playwright).
> Auth is hand-written **JWT (HMAC-SHA256)** with access and refresh tokens and **BCrypt** passwords. The frontend is **Next.js / TypeScript**."

---

## 2. Architecture (draw this on the board)

```
Next.js (localhost:3000)  ──HTTP/JSON + Bearer JWT──►  Spring Boot (localhost:8000)
                                                         │
   Controller  ─►  Service  ─►  Database (JdbcTemplate) ─►  PostgreSQL (19 tables)
   (REST, @Valid)  (business     (SQL from .sql files,
                    logic)        PreparedStatement)
                     │
                     ├─► GroqClient / HuggingFaceClient   (java.net.http.HttpClient)
                     ├─► Job APIs: Himalayas, Remotive, Jobicy, JSearch, Adzuna
                     ├─► PDFBox (read CV)   Pebble (HTML templates)   Playwright (HTML→PDF)
                     └─► JavaMail (verification email)
```

**Layers (package `com.jobfinder`):**

| Package | Role | Example |
|---|---|---|
| `controller` | REST endpoints, thin | `JobController`, `OnboardingController` |
| `service` | business logic | `JobFilterService`, `CvService`, `AuthService` |
| `db` | data access | `Database` (wraps `JdbcTemplate`), `SqlQueries` (loads `/sql/**/*.sql`) |
| `dto` | request/response **records** | `JobDtos.SearchResponse`, `AuthDtos.RegisterRequest` |
| `security` | JWT, BCrypt, rate limit, current user | `Jwt`, `TokenService`, `CurrentUserResolver` |
| `client` | outbound HTTP to AI | `GroqClient`, `Http` |
| `config` | beans, CORS, `.env` loading | `WebConfig`, `DatabaseConfig`, `AppSettings` |
| `web` | cross-cutting | `GlobalExceptionHandler`, `ApiException`, `SqlLogFilter` |

About 5,800 lines of Java in roughly 60 classes. There are 77 SQL files and 19 tables.

---

## 3. Java / Spring concepts used — know where each one is

| Concept | Where | One-line explanation |
|---|---|---|
| **Records** (Java 16+) | all DTOs, `JobSearchService.Query`, `Outcome`, `TokenService.RefreshToken` | Immutable data carriers. The compiler generates the constructor, getters, `equals`, `hashCode` and `toString`. |
| **Compact record constructor** | `ApplicationDtos.TrackJobRequest` | Normalises nulls: `location = Objects.requireNonNullElse(location, "")`. |
| **Pattern matching for `instanceof`** | `if (job.get("salary_min_raw") instanceof Double pay)`, `Jwt.decode` | Tests the type and binds the variable in one step, with no cast. |
| **Switch expressions** (`->`) | `JobSearchService.fetchAdzuna` employment type | No fall-through. |
| **Text blocks** `"""` | `JobGateService.SCHEMA` | Multi-line string literal. |
| **Streams + lambdas** | `JobFilterService`, `JobController` (`.stream().map().toList()`) | Declarative collection processing. |
| **Functional interfaces** | `Supplier<T>` in `Database.inTransaction`, `JobSearchService.collect` | Pass behaviour as a parameter. |
| **Generics** | `<T> T inTransaction(Supplier<T>)`, `TypeReference<Map<String,Object>>` | Type-safe reusable code. |
| **Enums** | `JobFilterService.Decision { ACCEPT, REJECT, INCONCLUSIVE }` | Fixed set of states. |
| **Custom exceptions** | `ApiException` (holds an `HttpStatus`), `InvalidTokenException`, `AiClientException` | Unchecked (`RuntimeException`) so they don't clutter signatures. |
| **try-with-resources** | `PDDocument` in `CvTextExtractor`, Playwright in `PdfService`, `ExecutorService` in `JobFreshnessChecker` | Auto-closes `AutoCloseable` resources. |
| **Multi-catch** | `catch (IOException \| IllegalArgumentException e)` in `Jwt.parse` | One handler for several exception types. |
| **Concurrency** | `CompletableFuture.supplyAsync(..., GATE_POOL)` (AI gate), `ExecutorService` + `Future` (freshness checker), `ConcurrentHashMap` (rate limiter, SQL cache) | Parallel I/O; thread-safe maps. |
| **Regex** (`Pattern`/`Matcher`) | years-of-experience and region-blocker rules, email/phone/URL extraction | Rule-based screening. |
| **java.time** | `OffsetDateTime`, `Duration`, `Instant` | Job age, token expiry. |
| **java.net.http.HttpClient** | `client/Http.java` | Built-in HTTP client (Java 11+). No external HTTP library. |
| **Annotations (custom)** | `@RateLimit(perMinute = 30)` read by `RateLimitInterceptor` through reflection (`getMethodAnnotation`) | Declarative cross-cutting behaviour. |
| **Static nested types / private constructor** | `Jwt` is a `final` utility class with a private constructor | Cannot be instantiated or subclassed. |

**Spring specifically:**
- **Dependency Injection (constructor injection)** everywhere. There is no `@Autowired` on fields. Fields are `final`, so they are immutable and easy to test.
- Stereotypes: `@RestController`, `@Service`, `@Repository` (`Database`), `@Component`, `@Configuration` + `@Bean`.
- **IoC container**: Spring creates the beans as singletons and wires them together.
- `@RestControllerAdvice` + `@ExceptionHandler` turns every exception into a JSON error with the correct HTTP status (`GlobalExceptionHandler`).
- **Bean Validation**: `@Valid`, `@NotNull`, `@Email`, `@Size`, `@Pattern` on records. A failure returns 422.
- **`HandlerMethodArgumentResolver`**: `CurrentUserResolver`. Any controller method that takes a `CurrentUser` parameter is authenticated automatically.
- **`HandlerInterceptor`**: `RateLimitInterceptor.preHandle`.
- **Servlet filter**: `SqlLogFilter extends OncePerRequestFilter`, plus the CORS filter at highest precedence.
- **HikariCP** connection pool (max 10) in `DatabaseConfig`.
- **Transactions**: `TransactionTemplate` through `db.inTransaction(() -> ...)`, used in `ApplicationService.track`. Upserting the job and creating the application are atomic.
- Jackson `SNAKE_CASE` naming, so Java `externalId` ↔ JSON `external_id`.

---

## 3A. OOP in this project — the 4 pillars + more (EXPECT THIS)

> One-liner: "The backend is layered OOP. Each class has one responsibility. Objects collaborate through constructor-injected dependencies. State is hidden behind methods, and frameworks call my code through interfaces."

### 1) Encapsulation — hide data, expose behaviour
- **`ApiException`** has a `private final HttpStatus status` field. It can only be read through the `status()` method, and it is set once in the constructor.
- **`Database`**: its `JdbcTemplate`, `TransactionTemplate` and `ObjectMapper` are all `private final`. Callers only see `fetchAll`, `fetchOne`, `execute` and `inTransaction`. *Nobody outside knows JDBC exists.* Its helpers `bind`, `toRow` and `readValue` are `private`.
- **`AppSettings`**: the raw `.env` map is private. The outside world gets typed getters (`groqModel()`, `accessTokenMinutes()`), and the private helpers are `get`, `getInt` and `getBool`.
- **`PasswordHasher`**: callers only call `hash()` and `matches()`. The 72-byte trimming (`limit`) is private.
- **Records** (`CurrentUser`, every DTO): their fields are automatically `private final`, so they are immutable.
- Access modifiers in use: `private` (helpers), package-private (`CvContent` is a `final class` with no `public`, visible only inside `service`), `protected` (`doFilterInternal`), `public` (the API).

### 2) Inheritance — "is-a"
- **Custom exception hierarchy**: `ApiException`, `InvalidTokenException`, `AiClientException` and `StorageService.StorageException` all **`extends RuntimeException`**. Each calls **`super(message)`** or **`super(message, cause)`** to reuse the parent constructor and keep the cause chain.
- **`SqlLogFilter extends OncePerRequestFilter`**: it inherits Spring's "run once per request" logic and only fills in its own part.
- **Records** implicitly extend `java.lang.Record`. Every class ultimately extends `Object`.

### 3) Polymorphism — one interface, many forms
- **Runtime polymorphism (method overriding, `@Override`)**:
  - `SqlLogFilter.doFilterInternal(...)` overrides the abstract hook in `OncePerRequestFilter`. This is the **Template Method pattern**: the parent defines the algorithm and the child fills in a step.
  - `RateLimitInterceptor.preHandle(...)` overrides `HandlerInterceptor`.
  - `CurrentUserResolver.supportsParameter(...)` and `resolveArgument(...)` override `HandlerMethodArgumentResolver`.
  - `WebConfig.addArgumentResolvers(...)` and `addInterceptors(...)` override `WebMvcConfigurer`.
  - Spring holds them as the **interface type** (`List<HandlerMethodArgumentResolver>`), then calls *my* implementation at runtime through **dynamic dispatch**.
- **Exception polymorphism**: `GlobalExceptionHandler` has a **catch-all `@ExceptionHandler(Exception.class)`**. Any subclass is caught by its most specific handler first, otherwise by the parent-type handler.
- **Compile-time polymorphism (overloading)**:
  - `ApiException(HttpStatus, String)` and `ApiException(HttpStatus, String, Throwable)` are **constructor overloading**.
  - `AiClientException(String)` and `AiClientException(String, Throwable)`.
  - `AppSettings.get(String key)` and `get(String key, String fallback)` are **method overloading** with the same name and different parameters.
- **Sealed interface + records** (Java 17): `public sealed interface AuthResponse permits SessionResponse, VerificationChallenge`. `AuthController.register/login` return `AuthResponse`, and the actual object is one of exactly two allowed types. That is polymorphism with a **closed** hierarchy.

### 4) Abstraction — show *what*, hide *how*
- **Interfaces** I implement: `HandlerInterceptor`, `HandlerMethodArgumentResolver`, `WebMvcConfigurer`. Spring depends only on the abstraction.
- **Abstract class** I extend: `OncePerRequestFilter`.
- **Service layer as abstraction**: a controller calls `search.search(query)` and `filter.filter(...)`. It has no idea about HTTP calls, regex or Groq.
- **`Database`** abstracts JDBC. **`GroqClient`** abstracts the HTTP + JSON details of the AI. **`PdfService.render(html)`** hides Playwright and the browser fallback.
- **Custom annotation `@RateLimit`** (`@interface`, `@Retention(RUNTIME)`, `@Target(METHOD)`): you declare *what* (`perMinute = 30`), and the interceptor handles *how*.

### Also mention: OOP design principles / patterns
| Principle / pattern | Where |
|---|---|
| **Single Responsibility** | `CvTextExtractor` only reads PDFs; `PasswordHasher` only hashes; `TokenService` only does tokens |
| **Composition over inheritance ("has-a")** | `JobFilterService` *has a* `JobGateService` and a `Database`; `JobGateService` *has a* `GroqClient` + `HuggingFaceClient` |
| **Dependency Inversion / DI** | everything arrives through the constructor, nothing calls `new Service()` |
| **Singleton** | Spring beans are singletons by default (one `Database` for the whole app) |
| **Template Method** | `OncePerRequestFilter` → `doFilterInternal` |
| **Strategy / Chain of fallback** | `JobGateService.evaluate`: Groq → HuggingFace → heuristic |
| **Builder** | `HttpRequest.newBuilder()...build()`, `HttpClient.newBuilder()`, `PebbleEngine.Builder` |
| **Utility class** | `Jwt`, `Values`, `SqlLog`: `final` class, `private` constructor, all `static` methods |
| **Immutable objects** | records, `final` fields, `List.of()` / `Map.of()` |
| **Facade** | `Database` wraps JdbcTemplate + TransactionTemplate + SqlQueries behind 4 methods |

### OOP cross-questions
- **Abstract class vs interface?** An abstract class can have state, constructors and partial implementation, and a class can extend only one. An interface defines a contract with no instance state, and a class can implement many (with `default` methods since Java 8). *Here:* `OncePerRequestFilter` is an abstract class (it shares filter logic), and `HandlerInterceptor` is an interface (a pure contract).
- **Overloading vs overriding?** Overloading = same name, different parameters, resolved at **compile time** (`ApiException` constructors). Overriding = same signature in a subclass, resolved at **runtime** (`preHandle`, `doFilterInternal`).
- **Why `@Override`?** The compiler checks that I'm really overriding. A typo in the name would fail to compile instead of silently creating a new method.
- **What does `super(...)` do in your exceptions?** It calls `RuntimeException`'s constructor so `getMessage()` and `getCause()` work. `ApiException` then adds its own `status` field. That is **extending** behaviour.
- **Why is `Jwt` `final` with a private constructor?** It is a stateless utility, so nobody should create it or subclass it.
- **What's a sealed interface?** It restricts which classes may implement it (`permits`). The compiler then knows every possible subtype.
- **Is a record OOP?** Yes. It is a class with encapsulated `private final` fields, a constructor and accessors, and it can implement interfaces (the auth records implement `AuthResponse`).
- **Where's composition?** `JobFilterService` contains a `JobGateService` and delegates the AI decision to it, instead of inheriting from it.

---

## 3B. Core Java fundamentals — mapped to real code

| Fundamental | In this project |
|---|---|
| **Classes & objects** | ~60 classes; Spring creates the objects (beans), and `new` is used for value objects like `new Window(minute, 1)` and `new LinkedHashMap<>()` |
| **Constructors** | constructor injection in every service; overloaded constructors in the exceptions; **compact record constructor** in `TrackJobRequest` |
| **`this` / `super`** | `this.db = db;` in constructors; `super(detail, cause)` in `ApiException` |
| **`static`** | `static final` constants (about 76 of them, e.g. `MAX_AI_CALLS = 8`, `ACCEPT_THRESHOLD = 0.20`), static helpers (`Values.str`, `Jwt.encode`), static nested types (`StorageService.StorageException`) |
| **`final`** | final fields (immutability), final classes (`Jwt`, `Values`, the DTO holder classes), final constants |
| **Primitive vs wrapper types** | `int perMinute`, `long minute`, `double overlap` vs `Double minimum` (wrapper, because salary may be **null**); autoboxing in `Map<String,Object>` |
| **String handling** | `String.join`, `strip()`, `toLowerCase(Locale.ROOT)`, `replaceAll(regex)`, `substring`, `StringBuilder` in `AtsScanner` (efficient concatenation in loops) |
| **Arrays** | `byte[]` for PDF bytes and HMAC signatures; `String[] parts = token.split("\\.")` in `Jwt`; `Arrays.equals` for the `%PDF` magic check |
| **Varargs** | `Database.fetchAll(String queryName, Object... params)` |
| **Control flow** | `for` loops (provider pages, binding params), enhanced `for (JsonNode item : payload.path("data"))`, `while` (password trim), `if/else`, ternary `?:`, `switch` and switch expressions |
| **Collections framework** | `List`/`ArrayList`, `Map`/`HashMap`/`LinkedHashMap` (keeps insertion order for JSON), `Set`/`HashSet`/`LinkedHashSet` (dedupe while keeping order), `ConcurrentHashMap` (thread-safe), immutable `List.of`/`Map.of` |
| **Generics** | `List<Map<String,Object>>`, `<T> T inTransaction(Supplier<T>)`, wildcard `Map<?, ?>` in `JobGateService.object()` |
| **Exception handling** | try/catch/finally, try-with-resources, multi-catch, custom exceptions, **checked** (`IOException`, `SQLException`) vs **unchecked** (`RuntimeException`), wrapping with a cause, `throws` clauses (`SqlLogFilter`) |
| **Interfaces & lambdas** | `Supplier`, `RowMapper` lambda `(resultSet, index) -> toRow(resultSet)`, method references `String::valueOf`, `ArrayList::new`, `where::contains` |
| **Streams API** | `filter`, `map`, `limit`, `skip`, `anyMatch`, `allMatch`, `sorted` with `Comparator.comparing(...).reversed()`, `toList()` |
| **Enums** | `Decision { ACCEPT, REJECT, INCONCLUSIVE }` |
| **Annotations** | built-in (`@Override`, `@SuppressWarnings("unchecked")`), framework (`@Service`, `@GetMapping`, `@Valid`), **custom** (`@RateLimit`) |
| **Reflection** | `method.getMethodAnnotation(RateLimit.class)` reads the annotation at runtime |
| **Multithreading** | `ExecutorService`, `Future`, `CompletableFuture`, daemon threads, `Thread.currentThread().interrupt()`, `ConcurrentHashMap`, **`ThreadLocal`** in `SqlLog` (one SQL log per request thread) |
| **I/O** | `Files.readAllLines` (`.env`), `InputStream` from classpath (SQL files, templates), byte arrays for PDFs |
| **Packages & imports** | `com.jobfinder.{controller,service,db,dto,security,client,config,web,util,ats}` |
| **`equals` / `hashCode`** | records generate them; `HashSet<String>` dedupe depends on them |
| **Date/Time API** | `OffsetDateTime`, `Instant`, `Duration`, `ZoneOffset.UTC`, `DateTimeFormatter` |
| **Unit testing (JUnit 5)** | `@Test`, `assertEquals`, `assertThrows` in `JwtTest` and `ScreeningRulesTest` |
| **`main` method** | `JobfinderApplication.main` → `SpringApplication.run(...)`; `AtsScanner` is a separate command-line program with its own `main` |
| **JVM / build** | Maven compiles to bytecode (`target/classes`) and `spring-boot:run` launches it on the JVM (Java 21) |

### Fundamentals cross-questions
- **JDK vs JRE vs JVM?** The JVM runs bytecode. The JRE = JVM + libraries. The JDK = JRE + compiler (`javac`) + tools. Maven uses the JDK to compile.
- **Why `Double` not `double` for salary?** A primitive can't be `null`, and many jobs have no salary. The wrapper lets `null` mean "not stated".
- **`==` vs `.equals()`?** `==` compares references; `.equals` compares values. I use `"access".equals(claims.get("type"))`. Putting the literal first is null-safe.
- **Why `LinkedHashMap` over `HashMap`?** It keeps insertion order, so JSON fields come out in a predictable order.
- **`String` vs `StringBuilder`?** `String` is immutable, so building it in a loop creates many objects. `StringBuilder` is mutable, so `AtsScanner` uses it for CSV output.
- **Why `HashSet` for dedupe?** `add()` returns `false` for duplicates in O(1) on average. See `JobSearchService.dedupe`.
- **What is `ThreadLocal`?** Each thread gets its own copy. Tomcat serves each request on one thread, so `SqlLog` collects the SQL for *that request only*.
- **What is garbage collection?** The JVM frees unreachable objects automatically. try-with-resources still matters for *non-memory* resources such as files, PDFs and browsers.
- **Checked vs unchecked?** Checked exceptions (`IOException`) must be caught or declared; the compiler forces it. Unchecked ones (`RuntimeException`) don't need to be.
- **What's autoboxing?** Automatic `int` ↔ `Integer` conversion, for example putting `salaryMin` (an `int`) into a `Map<String,Object>`.
- **What does `final` mean on a variable / method / class?** It can't be reassigned / can't be overridden / can't be extended.
- **`static` method vs instance method?** A static method belongs to the class and needs no object (`Values.str(x)`). An instance method needs an object and can use its fields (`db.fetchAll(...)`).

---

## 4. Key flows (be ready to trace the code)

### Auth
1. `POST /api/auth/register` → `VerificationService` generates a 6-digit code with **`SecureRandom`**. It stores only a **salted SHA-256 hash** of the code, inside a signed verification token. Nothing is stored in the DB until the user is verified.
2. `POST /api/auth/verify-email` → compares the hashes with `MessageDigest.isEqual` (**constant-time**). Then the user is created with a **BCrypt** password (cost 12).
3. Login returns an **access token** (15 min) and a **refresh token** (7 days, has a `jti` and is stored in `refresh_sessions`, so it can be revoked).
4. Every request sends `Authorization: Bearer <jwt>`. `CurrentUserResolver` checks the signature, expiry, `iss`, `aud` and `type == "access"`, then loads the user.

**JWT internals (`Jwt.java`, written by hand):** `base64url(header).base64url(claims).base64url(HMAC-SHA256(secret, header.claims))`. It supports **key rotation** through the `kid` header (`JWT_KEYS_JSON`). It also rejects a token whose `alg` doesn't match, which blocks the `alg: none` attack.

### CV parse
`upload-cv` (multipart) → checks the content type, size and **`%PDF` magic bytes** → PDFBox extracts the text → regex pulls out email/phone/links → Groq returns JSON (JSON mode, with a retry without JSON mode) → **preview only, no DB write** → the user edits it → `confirm-cv` writes it in a transaction.

### Job search (`GET /api/jobs/search`)
1. `JobSearchService` calls each provider through `collect(...)`. **If one provider fails, the others still return results.** You get a 502 only if every provider fails.
2. Dedupe by `source|external_id`. Keep a job only if its title matches the tag (`relevant()`). Apply the salary floor.
3. `JobFilterService` Tier 0 (regex/rules) returns ACCEPT, REJECT or INCONCLUSIVE.
4. Tier 1: up to 8 inconclusive jobs go to `JobGateService`, **2 in parallel**. The fallback chain is **Groq → HuggingFace → keyword heuristic**, so a search never fails because of the AI.
5. Results are sorted newest first. Search results are **never stored**. A job is written to the DB only when the user tracks it.

### Tracking / documents
`track` → upsert into `jobs` (`ON CONFLICT (external_job_key)`) → insert into `applications`. `prepare-apply` sets the status to `applying`. `documents/generate` asks Groq for content and renders the Pebble template → `compile` turns the HTML into a PDF with Playwright headless Chromium (falls back to Edge/Chrome).

---

## 5. Database (PostgreSQL, 19 tables)

Core tables: `users`, `refresh_sessions`, `canonical_tags`, `user_selected_tags`, `user_education`, `user_experiences` + `experience_keywords`, `user_projects` + `project_keywords`, `keywords`, `job_sources`, `jobs`, `applications`, `application_status_history`, `generated_documents`, `document_versions`.

- **Many-to-many** tables: `experience_keywords`, `project_keywords`, `user_selected_tags`, `job_tags`.
- **Why plain JDBC and not JPA/Hibernate?** I wanted full control of the SQL. Each query lives in its own `.sql` file (`resources/sql/<area>/<name>.sql`), which `SqlQueries` loads and caches in a `ConcurrentHashMap`. That gives no N+1 surprises, and it lets me use Postgres features like `ON CONFLICT`, `jsonb`, `RETURNING` and `citext`.
- **SQL injection safety:** every query uses `PreparedStatement` with `?` placeholders (`Database.bind`). User input is never concatenated into SQL. The one dynamic piece, the schema name, is checked against the regex `[A-Za-z_][A-Za-z0-9_]*`.
- Rows come back as `Map<String,Object>`. `Database.readValue` converts `timestamptz` → ISO string and `jsonb` → Java objects.

---

## 6. Likely cross-questions — with answers

**Q: Why Spring Boot?**
A: It gives auto-configuration, an embedded Tomcat, dependency injection, validation and JDBC support out of the box, so I focus on business logic. Java 21 gives records, pattern matching and switch expressions.

**Q: What is dependency injection? Show me.**
A: Spring creates the objects and passes them in. For example, `JobController(Database db, JobSearchService search, JobFilterService filter)` is a constructor parameter list, so I never write `new`. That gives loose coupling, and I can pass mocks in tests.

**Q: Difference between `@Component`, `@Service`, `@Repository`, `@Controller`?**
A: All of them register a bean. The others are specialisations for meaning: `@Repository` also translates SQL exceptions into Spring's `DataAccessException`, and `@RestController` = `@Controller` + `@ResponseBody`.

**Q: How does a request reach the controller?**
A: Tomcat → filters (CORS, `SqlLogFilter`) → `DispatcherServlet` → interceptor (`RateLimitInterceptor.preHandle`) → argument resolvers (`CurrentUserResolver` builds `CurrentUser`; Jackson parses the body; `@Valid` runs) → controller → service → `Database` → JSON response. Exceptions go to `GlobalExceptionHandler`.

**Q: How is authentication done without Spring Security?**
A: A custom `HandlerMethodArgumentResolver`. If a controller method has a `CurrentUser` parameter, the resolver reads the Bearer token, verifies the HMAC signature and expiry, and loads the user. Otherwise it throws `ApiException(401)`. Only `spring-security-crypto` is used, for BCrypt.

**Q: Why two tokens?**
A: The access token is short-lived (15 min), so a leaked token expires quickly. The refresh token lives 7 days and is tied to a `refresh_sessions` row. **Rotation:** every refresh issues a new refresh token with a new `jti`, and the row is locked while it updates (`SELECT … FOR UPDATE`). **Reuse detection:** if an *old* refresh token is presented again, it was probably stolen, so the whole session is revoked (`AuthService.refresh`). Logout sets `revoked_at` on the session.

**Q: Why BCrypt and not SHA-256 for passwords?**
A: BCrypt is deliberately slow (cost 12 = 2^12 rounds) and has a built-in salt, which defeats brute force and rainbow tables. SHA is fast, which is bad for passwords. BCrypt only reads the first 72 bytes, so `PasswordHasher.limit` trims safely on code-point boundaries.

**Q: Why `MessageDigest.isEqual` and not `Arrays.equals` / `equals`?**
A: It runs in constant time, which prevents timing attacks on signature and code comparison.

**Q: How do you prevent SQL injection?**
A: Every query uses `PreparedStatement` with `?` parameters. See `Database.bind`.

**Q: How does the rate limiter work? Is it thread-safe?**
A: It is a fixed one-minute window keyed by `IP|method`. `ConcurrentHashMap.merge` is atomic per key, and `Window` is an immutable record, so there are no race conditions. Limitation: the state lives in memory, so it isn't shared between multiple servers. In production I'd use Redis.

**Q: Where is multithreading, and why?**
A: (1) The AI gate: `CompletableFuture.supplyAsync` on a fixed pool of 2 daemon threads. Each AI call is network I/O (about 1–3 s), so parallel calls cut the wait. I capped it at 2 because Groq's free tier rate-limits (HTTP 429). (2) `JobFreshnessChecker` uses an `ExecutorService` to check whether old postings are still live. It restores the interrupt flag with `Thread.currentThread().interrupt()` when it catches `InterruptedException`.

**Q: What happens if `CompletableFuture` throws?**
A: `gateJob` catches the `RuntimeException` and keeps the job with the algorithmic reason, so `join()` never throws. Also, `JobGateService.evaluate` has its own fallback chain.

**Q: Checked vs unchecked exceptions in your code?**
A: My domain exceptions extend `RuntimeException` (unchecked), so they pass through layers and `GlobalExceptionHandler` maps them to HTTP. Checked ones such as `IOException` are caught at the edge and wrapped, for example `ApiException(BAD_GATEWAY, "Search API unreachable", error)`, keeping the cause.

**Q: What's a record? Can it have methods? Is it mutable?**
A: It is a final, immutable data class. Its fields are `private final`. It can have methods, static fields and a compact constructor for validation, but no extra instance fields.

**Q: Why `Map<String,Object>` instead of entity classes?**
A: Honest answer: it's quick and flexible with hand-written SQL, and the JSON output maps straight from it. The downside is no compile-time type safety. DTOs at the API boundary are typed records. If I refactored, I'd map rows to records with a `RowMapper`.

**Q: How is the `.env` file read?**
A: `AppSettings` checks process environment variables first, then parses `backend/.env` by hand (quotes and trailing `#` comments). Bad JWT key configuration fails fast in the `TokenService` constructor.

**Q: What if the database is down?**
A: Hikari is set with `initializationFailTimeout(-1)`, so the app still starts. Requests then get a clean **503** from `handleDatabaseDown`.

**Q: How does job filtering decide? Explain the skill overlap.**
A: The job text is tokenised and lightly stemmed (strip -ing/-ed/-er/-s…). Overlap = (user skills found in the job) ÷ (total user skills). At **≥ 20%** the job is accepted. A required-years regex (for example `3-5 years experience`) rejects the job if it asks for more than 4 years beyond the user's. Region regexes reject phrases like "US citizens only" for an India search.

**Q: Why not just send everything to the AI?**
A: Cost, latency and rate limits. Tier 0 is free and instant, and it settles most jobs. Only unclear ones (max 8 per search) reach the AI.

**Q: How do you call external APIs?**
A: With Java's built-in `java.net.http.HttpClient` (HTTP/1.1, 10 s connect timeout, 30 s request timeout). JSON is parsed with Jackson's `ObjectMapper.readTree` → `JsonNode`.

**Q: How do you read the PDF?**
A: `Loader.loadPDF(bytes)` → `PDFTextStripper` inside try-with-resources. If the text is shorter than `CV_MIN_TEXT_CHARS` (200), it's probably a scanned image, so it's rejected.

**Q: CORS — what and why?**
A: The browser blocks calls between origins (3000 → 8000) unless the server sends `Access-Control-Allow-Origin`. `WebConfig` registers a `CorsFilter` at `HIGHEST_PRECEDENCE` with the allowed origins from `.env`.

**Q: Testing?**
A: JUnit 5 tests: `JwtTest` (sign/verify, tamper detection, expiry) and `ScreeningRulesTest` (regex years extraction, skill overlap). Run them with `mvn test`. I also tested the whole flow end to end through the REST API.

**Q: Biggest bug you hit?** (tell this — it shows real debugging)
A: "Search showed zero jobs. I traced it: the RapidAPI key in `.env` was actually a comment, so JSearch was skipped, and Adzuna had no keys either, so there was no data source at all. I added three keyless providers (Himalayas, Remotive, Jobicy). I made each provider fail on its own with a `collect()` wrapper that turns a provider's error into a warning instead of a crash. I added a title-relevance filter. And I fixed a mismatch: the search asked for a month of jobs but the filter dropped anything older than 7 days. A second bug: tracked jobs lost their source because new sources weren't in `job_sources`, so the UI couldn't show them as tracked. I fixed it with an upsert (`ON CONFLICT … RETURNING`)."

**Q: Limitations / future work?** (always have this ready)
- The rate limiter is in memory, so it isn't shared across servers → Redis.
- The AI gate is capped at 8 calls because of the free tier.
- Raw `Map` rows instead of typed entities.
- Keyless boards list only remote jobs; on-site Indian jobs need the JSearch subscription.
- No scheduled job alerts yet. Docker and CI would be next.

---

## 7. Demo script (3 min)

1. Start: `cd backend; mvn spring-boot:run`, and `cd frontend; npm run dev`.
2. Log in → **Onboarding**: upload a CV → show the parsed preview → **Save**.
3. **Jobs**: pick *backend*, country IN → Search → point out the "Last 24 hours / 7 days / Older" sections and the *why fit* text (Groq vs algorithmic).
4. **Track** a job → **Dashboard** shows it → open it → **Prepare apply** → generate the CV → export the PDF.
5. If asked about the code: open `JobFilterService.filter` (the two tiers), `CurrentUserResolver` (auth), `Database.bind` (SQL injection safety).

## 8. Quick facts to memorise

- Java **21**, Spring Boot **3.5.16**, Maven, PostgreSQL, HikariCP pool **10**
- Port **8000** (backend), **3000** (frontend)
- Access token **15 min**, refresh **7 days**, BCrypt cost **12**, HMAC **SHA-256**
- Rate limits: search **30/min**, CV upload **5/min**, confirm **10/min**
- CV max **5 MB**, min text **200 chars**
- Filter: overlap threshold **20%**, max job age **30 days**, seniority gap **4 years**, AI calls **≤ 8**, gate threads **2**
- Libraries: PDFBox 3.0.8, Pebble 3.2.4, Playwright 1.65, Jackson, JavaMail
