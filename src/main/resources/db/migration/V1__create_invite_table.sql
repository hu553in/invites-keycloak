create table if not exists invite (
    id uuid primary key,
    realm text not null,
    token_hash text not null,
    salt text not null,
    email varchar(254) not null,
    created_by text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    max_uses integer not null default 1 check (max_uses >= 1),
    uses integer not null default 0 check (uses >= 0),
    revoked boolean not null default false,
    roles text[] not null
);

create unique index if not exists invite_email_realm_uidx on invite (email, realm);
create index if not exists invite_expires_at_idx on invite (expires_at);
create index if not exists invite_realm_idx on invite (realm);
