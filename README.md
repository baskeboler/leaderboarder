# Leaderboarder

Minimal MVP backend for a credit-driven leaderboard game.

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
