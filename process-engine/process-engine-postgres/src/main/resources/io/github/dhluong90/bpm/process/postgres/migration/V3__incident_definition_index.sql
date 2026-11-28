-- The incident list of one definition version (#91, PRE-395): newest first within one status, found by the definition.
-- idx_pre_incident_status leads with (tenant_id, status) and would walk every incident of the tenant to find those of
-- one version; this index leads with the version, so the page is read in order with a LIMIT and no sort.
-- Additive: nothing is dropped or changed, the open-incident count per step keeps idx_pre_incident_node.

CREATE INDEX idx_pre_incident_definition_status ON pre_incident
    USING btree (tenant_id, definition_id, status, created_at DESC, id DESC);
