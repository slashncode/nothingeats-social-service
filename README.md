# nothingeats-social-service

Social bounded context of [NothingEats](https://github.com/slashncode/nothingeats): an anonymous live feed of what everyone is not eating, and a weekly XP leaderboard under pseudonyms.

## What it does

- **Feed**: every placed order, levels from 3 on and gold badges become a feed line ("someone did not eat 2 dishes from Phantom Pizza"). Anonymous by construction: no name, no place, no account. The service keeps the user id with a line only to erase it; it never leaves the service. The newest 100 lines are kept.
- **Live**: new lines go to every open feed stream through Valkey pub/sub, so the pod that consumed the event does not need to hold the browser's connection.
- **Leaderboard**: XP per ISO week (Monday to Sunday, Hamburg time) in a Valkey sorted set per week, only for customers who joined. Joining picks a random pseudonym ("Satter Seehund 42" / "Full Seal 42"), kept when leaving and joining again; leaving takes them off this week's board at once. Boards expire after six weeks.
- **GDPR**: `user.deleted` erases the player, their feed lines and their places on recent boards.

## API (`/api/social/v1`)

| Method | Path | Auth | What |
|---|---|---|---|
| `GET` | `/feed?limit=` | – | Newest feed lines (default 20, at most 50) |
| `GET` | `/feed/stream` | – | Server-sent events: `ready`, then `feed.item` per new line, a `: ping` every 15 s |
| `GET` | `/leaderboard` | – | This week's top 10: rank, pseudonym (in `?lang=` or Accept-Language), XP |
| `GET` | `/me` | signed in | Own pseudonym, whether on the board, this week's XP and rank |
| `PUT` | `/me/leaderboard` | signed in | `{join: true|false}` |

Consumes `ordering.order.v1` (`placed`), `loyalty.reward.v1` (`reward.earned`) and `identity.user.v1` (`user.deleted`). Publishes no events.

Share cards (server-rendered images) are not built yet: they need fonts in the distroless image.

## Run

```sh
./gradlew build                       # needs Docker (Testcontainers: Postgres, Kafka, Valkey)
./gradlew :app:run --args=migrate     # DATABASE__URL, DATABASE__USER, DATABASE__PASSWORD
./gradlew :app:run                    # VALKEY__HOST/PORT/PASSWORD, KAFKA__BOOTSTRAP_SERVERS
```

## License

MIT
