CREATE TABLE model_connections (
 org_id uuid NOT NULL REFERENCES organizations(id),
 id uuid NOT NULL,
 owner_id uuid NOT NULL REFERENCES app_users(id),
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 80),
 provider text NOT NULL CHECK(provider IN ('smart-router','openai','anthropic')),
 encrypted_key text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(org_id,id)
);
GRANT SELECT,INSERT,DELETE ON model_connections TO qc_app;
ALTER TABLE model_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_connections FORCE ROW LEVEL SECURITY;
CREATE POLICY private_connection ON model_connections
 USING (org_id = nullif(current_setting('app.org_id',true),'')::uuid
 AND owner_id = nullif(current_setting('app.user_id',true),'')::uuid)
 WITH CHECK (org_id = nullif(current_setting('app.org_id',true),'')::uuid
 AND owner_id = nullif(current_setting('app.user_id',true),'')::uuid);
