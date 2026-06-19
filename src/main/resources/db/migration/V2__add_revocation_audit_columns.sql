alter table invite
    add column if not exists revoked_at timestamptz,
    add column if not exists revoked_by text;
