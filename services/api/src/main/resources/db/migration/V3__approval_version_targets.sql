ALTER TABLE approvals ADD COLUMN component_id uuid, ADD COLUMN revision integer;
ALTER TABLE approvals ADD FOREIGN KEY(org_id,component_id,revision) REFERENCES component_versions(org_id,component_id,revision);
