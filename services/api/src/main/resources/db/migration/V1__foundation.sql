-- Domain and tenancy tables are introduced with authenticated organizations in step 2.
CREATE TABLE foundation_metadata (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    installed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO foundation_metadata (singleton) VALUES (true);
