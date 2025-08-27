# Leaderboarder

Minimal MVP backend for a credit-driven leaderboard game.

## Authentication

Users are created with a password and can obtain an access token via
`POST /login` with their `username` and `password`. Subsequent API requests
must include the token in an `Authorization: Bearer <token>` header.
Unauthenticated requests to protected endpoints will receive a 401 response.

## Development

### Running the server

```
lein run
```

This boots the Integrant system and starts a Jetty server. Configuration is environment-driven with sensible defaults:

- PORT: HTTP port (default 3000)
- SCHEDULER_INTERVAL_MS: Interval in milliseconds for the periodic credit incrementer (default 60000)
- DB_URL: Full JDBC URL for the database connection; if not set, an in-memory H2 DB is used (`jdbc:h2:mem:leaderboarder-mvp`).

Examples:

Windows PowerShell
```
$env:PORT="4000"
$env:SCHEDULER_INTERVAL_MS="15000"
$env:DB_URL="jdbc:h2:mem:leaderboarder-mvp;DB_CLOSE_DELAY=-1"
lein run
```

POSIX shell
```
export PORT=4000
export SCHEDULER_INTERVAL_MS=15000
export DB_URL="jdbc:h2:mem:leaderboarder-mvp;DB_CLOSE_DELAY=-1"
lein run
```

Note: If DB_URL is not set, the default is an in-memory H2 database with dbtype "h2:mem" and dbname "leaderboarder-mvp".

### Running the tests

```
lein test
```

The test suite exercises key leaderboard functions such as user lookup,
credit spending, and time-of-day filtering.

### Building the ClojureScript frontend

Install JavaScript tooling and compile the browser bundle with
[`shadow-cljs`](https://shadow-cljs.github.io/docs/UsersGuide.html):

```
npm install
```

During development you can rebuild the UI with:

```
npm run build
```

Running `lein uberjar` automatically invokes the same build step and
packages the generated assets under `resources/public` into the final
jar. The build outputs to `resources/public/js/app.js` which is
referenced by `index.html`.
