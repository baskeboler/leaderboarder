### Overview
Below is an actionable, logically ordered improvement checklist for the Leaderboarder project. Because the environment is read-only here, please copy the checklist into a new file at:

- docs\tasks.md

Each task is phrased to be independently actionable, with concrete file references where possible.

### Enumerated Checklist
1. [x] Introduce environment-driven configuration (profiles or env vars) for DB, server port, and scheduler interval (replace hardcoded values in src\leaderboarder\core.clj config) and document in README.md.
2. [ ] Replace deprecated clojure.java.jdbc with next.jdbc across the codebase for better performance and maintenance.
3. [ ] Centralize DB access patterns: introduce a small repository layer (e.g., src\leaderboarder\repo.clj) that exposes pure functions for user and leaderboard CRUD and hides HoneySQL details.
4. [ ] Add structured logging (e.g., clojure.tools.logging + logback) for request handling, auth events, errors, and scheduler runs.
5. [ ] Introduce a global exception/response error middleware to convert exceptions to consistent JSON error payloads (4xx/5xx) with correlation IDs.
6. [ ] Implement input validation using clojure.spec or malli for all request bodies (users, login, credits/use, leaderboards), returning 400 with validation details.
7. [ ] Replace in-memory token auth with signed, expiring tokens (e.g., JWT via buddy-sign) or a DB-backed session table with expiry and revocation.
8. [ ] Add login attempt throttling/rate limiting (per-IP and per-username) to mitigate brute-force attacks; consider ring-rate-limit middleware or a simple in-memory/LRU store initially.
9. [ ] Ensure HTTPS in production (enforce X-Forwarded-* headers behind a proxy) and set secure cookie/headers (HSTS, no sniff, frame options) via ring-headers middleware.
10. [ ] Add CSRF protection strategy for any cookie-based flows; for pure token-based API, clearly disable anti-forgery in site-defaults and document rationale.
11. [ ] Introduce password policy (minimum length/entropy), and store password hashing settings (buddy hashers options) in config; add password change endpoint.
12. [ ] Update migrations to add created_at and updated_at columns to users and leaderboards; ensure last_active gets updated on relevant actions.
13. [ ] Add DB indexes: users.username (unique already), users.score, users.last_active, leaderboards.creator_id; verify H2/Postgres SQL.
14. [ ] Add foreign key constraints with ON DELETE behavior for leaderboards.creator_id referencing users(id), plus NOT NULL where appropriate (name, creator_id, min_users).
15. [ ] Provide down migrations for 001-create-users and 002-create-leaderboards to support rollbacks.
16. [x] Fix HoneySQL raw expression usage in time-of-day filter: do not pre-format sub-expressions; use [:raw "EXTRACT(HOUR FROM last_active)"] directly.
17. [x] Add server-side update of users.last_active on authenticated requests (e.g., in wrap-auth or per write operation) to support time-of-day filters.
18. [x] Harden use-credit concurrency: perform a conditional update with WHERE credits > 0 and check row-count to avoid race conditions and negative credits.
19. [x] In db/use-credit, validate action against an allowlist (e.g., :increment-self and :attack only) and return meaningful error responses.
20. [x] In db/create-user, enforce username normalization (trim, lowercase if desired) and uniqueness error handling to return 409 Conflict.
21. [ ] Add endpoint to GET /me (current user profile) and POST /logout (token revocation for server-side sessions or a no-op for JWT with client discard).
22. [ ] Add pagination parameters to leaderboard retrieval (limit/offset or cursor) and expose via GET /leaderboards/:id?limit=&cursor=.
23. [ ] Revisit create-leaderboard acceptance rule "creator must be first"; make this a toggle or business rule documented and validated.
24. [ ] Store leaderboard filters as structured JSON with a defined schema (spec/malli) and, for Postgres, consider JSONB with indexes; abstract for H2 compatibility.
25. [ ] Extend test coverage: add unit tests for auth middleware, route handlers, error cases, and validation failures.
26. [ ] Add integration tests that run against an ephemeral DB and exercise full request/response flows (ring.mock + integrant.system lifecycle).
27. [ ] Add property tests for use-credit invariants (never negative credits; score only changes when credit successfully deducted).
28. [ ] Add tests for scheduler (credit incrementer): verify it increments exactly once per tick and shuts down cleanly, using a short interval in tests.
29. [ ] Introduce test fixtures to create sample users/leaderboards and helpers to start/stop the Integrant system per test suite/module.
30. [ ] Parameterize scheduler interval and disable it by default in tests to avoid flakiness; enable in a dedicated scheduler test namespace only.
31. [ ] Optimize leaderboard queries for portability: replace EXTRACT with vendor-neutral functions or conditional SQL per adapter; verify in H2 and Postgres.
32. [ ] Add safe defaults to build-leaderboard-query: enforce a maximum limit to prevent large scans; validate filters against a schema and ignore unknowns explicitly (already partially done, but validate values too).
33. [ ] Improve build-leaderboard-query stability: add secondary sort keys that are deterministic and indexed (id already used; add tie-break comment and tests that assert ordering stability).
34. [ ] Improve performance by reducing select * in leaderboard queries: select only needed columns for list views, add dedicated detail endpoint for full user.
35. [ ] Add metrics (e.g., Micrometer via JVM integration or simple counters) for request counts, latency, DB timings, and scheduler executions.
36. [ ] Add basic health and readiness endpoints (/healthz, /readyz) including DB connectivity and migration status checks.
37. [ ] Add graceful shutdown hooks so Jetty stops on SIGTERM and Integrant halts components; verify no job runs mid-shutdown.
38. [ ] Introduce configuration and profiles for dev/test/prod: different DBs, logging levels, and site-defaults; optionally Dockerize with a docker-compose for Postgres.
39. [ ] Ensure a static UI is served correctly: add resources\public\index.html (if missing) and align cljs output path with Ring resource path.
40. [ ] Expand the CLJS UI to cover credit spending and leaderboard creation/display; add small client-side validation and error display.
41. [ ] In CLJS fetch effect, add handling for non-2xx responses by checking response.ok and using status codes to set user-friendly errors.
42. [ ] Add a minimal design system for UI components (input/button) with accessibility attributes (labels, aria-*), and form submission on Enter.
43. [ ] Add CSRF-safe pattern for future cookie auth in UI; document how token is stored (avoid localStorage for high-security contexts; prefer memory + refresh route).
44. [ ] Add basic linting/formatting: clj-kondo config, cljfmt task, and hook into CI.
45. [ ] Set up a CI pipeline (GitHub Actions or similar): run lein test, lint, and produce an uberjar artifact on main branch.
46. [ ] Add logging and error IDs to responses and expose correlation ID in headers to correlate client/server logs.
47. [ ] Add API documentation (OpenAPI/Swagger) for all routes with request/response schemas and examples.
48. [ ] Document security model, token lifetime, and threat model in README.md; add a SECURITY.md with a vulnerability disclosure process.
49. [ ] Review licensing and add a LICENSE file compatible with dependencies.

### Code-level Pointers
- File: src\leaderboarder\db.clj
  - time-of-day->predicate: change let hour-expr from (sql/format [:raw ...]) to [:raw "EXTRACT(HOUR FROM last_active)"] and adjust tests accordingly.
  - use-credit: change to two-phase conditional updates:
    - UPDATE users SET credits = credits - 1 WHERE id = ? AND credits > 0
    - If 1 row affected, then perform the score update depending on action; otherwise return an error/no-op indicator.
  - create-user: wrap insert in try/catch to map constraint violations to HTTP 409 at the route layer; normalize and validate fields.
- File: src\leaderboarder\core.clj
  - Add validation to POST bodies before calling db functions; return 400 on invalid input.
  - Replace tokens atom with JWT verification or DB-backed sessions; add logout route.
  - Update wrap-auth to parse Bearer tokens, verify expiration/claims; set :user-id only when valid.
  - Update make-handler: add error middleware and security headers.
  - Config: externalize via env; use integrant.repl for dev lifecycle.
- Migrations:
  - Add .down.sql files and additional indexes/constraints as above; consider Postgres dialect for production.
- Tests:
  - Add new test namespaces: auth_test.clj, routes_test.clj, repo_test.clj, scheduler_test.clj; use ring.mock.request and integrant init/halt.

### Next Steps
- Prioritize items 2–9 and 13–21 for immediate security and correctness gains.
- Then address performance (14, 32–35), DX/ops (36–39, 45–47), and documentation (48–50).
