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

This boots the Integrant system and starts a Jetty server on port 3000
backed by an in-memory H2 database.

### Running the tests

```
lein test
```

The test suite exercises key leaderboard functions such as user lookup,
credit spending, and time-of-day filtering.
