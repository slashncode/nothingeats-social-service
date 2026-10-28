# CLAUDE.md

Social service of NothingEats (anonymous live feed, weekly leaderboard under pseudonyms). Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers (Postgres, Kafka, Valkey).
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`
- `./gradlew :app:jibDockerBuild -Djib.from.platforms=linux/arm64` — local image

## Rules

- Respect the module direction: `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- Privacy first: feed lines are anonymous (no name, no place); the stored user id is only for erasure and never appears in a response or on the stream. The leaderboard shows only players who joined, under a random pseudonym.
- `user.deleted` must erase everything: player, feed lines, places on recent boards.
- Live lines go through `FeedBroadcast` (Valkey pub/sub), never straight to a stream. Stream tests use a real socket.
- Wiring happens only in `app/.../ServiceComponent.kt`; lifecycle components, health checks, routes, event handlers and cron tasks are contributed with `@IntoSet`.
- Schema changes are new Flyway files in `adapters/src/main/resources/db/migration`; never edit an applied migration.
- Event handlers are tolerant readers of other services' contracts: decode only the fields you need.
