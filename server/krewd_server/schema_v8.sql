-- Version 8: where the agents' Brave says it is. The owner applies a location in the app (typed, from the phone's
-- own GPS, a favorite or a recent one); "recent" rows are the last ones applied (the newest is the current one),
-- "favorite" rows are the owner's named places.
create table if not exists browser_locations (
    id          uuid primary key,
    kind        text not null check (kind in ('favorite', 'recent')),
    name        text,                    -- a favorite's label; on a recent, the favorite it came from, if any
    lat         double precision not null,
    lon         double precision not null,
    accuracy    double precision not null,  -- metres
    source      text not null default 'manual',  -- phone, manual, favorite, recent
    created_at  timestamptz not null default now(),
    applied_at  timestamptz              -- recents: when it was last applied
);
create index if not exists browser_locations_recent on browser_locations (applied_at desc) where kind = 'recent';
