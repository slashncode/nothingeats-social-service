-- The live feed: anonymous lines (no name, no place). user_id is kept only to erase a person's
-- lines when the account is deleted; it never leaves the service.
create table feed_items
(
    id                    uuid primary key,
    user_id               text        not null,
    kind                  text        not null,
    at                    timestamptz not null,
    restaurant_name       text,
    items                 integer,
    money_not_spent_cents bigint,
    level                 integer,
    badge_de              text,
    badge_en              text
);

create index feed_items_at on feed_items (at desc);
create index feed_items_user on feed_items (user_id);

-- Customers who joined the leaderboard (or did once): a random pseudonym, never the account.
create table players
(
    user_id        text primary key,
    adjective      integer not null,
    animal         integer not null,
    number         integer not null,
    on_leaderboard boolean not null
);
