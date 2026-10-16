ALTER TABLE app_users ADD COLUMN email_verified_at timestamptz;
ALTER TABLE model_connections ADD COLUMN key_revision integer NOT NULL DEFAULT 1;
GRANT UPDATE(key_revision) ON model_connections TO qc_app;
CREATE TABLE identity_tokens (
 token_hash text PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES app_users(id),
 purpose text NOT NULL CHECK(purpose IN ('verify','reset')),
 expires_at timestamptz NOT NULL,
 used_at timestamptz
);
CREATE INDEX identity_tokens_user ON identity_tokens(user_id,purpose);
CREATE TABLE auth_limits (bucket text PRIMARY KEY, started_at timestamptz NOT NULL, attempts integer NOT NULL);
GRANT SELECT,INSERT,UPDATE,DELETE ON identity_tokens,auth_limits TO qc_app;
