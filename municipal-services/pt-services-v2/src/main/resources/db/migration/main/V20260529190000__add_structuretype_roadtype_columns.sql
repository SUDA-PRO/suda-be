-- Add structureType to eg_pt_propertydetail_v2 (property-level construction/structure type)
ALTER TABLE eg_pt_propertydetail_v2
    ADD COLUMN IF NOT EXISTS structuretype VARCHAR(64);

-- Add roadType to eg_pt_address_v2
ALTER TABLE eg_pt_address_v2
    ADD COLUMN IF NOT EXISTS roadtype VARCHAR(64);

-- Also add to audit tables so audit trail is consistent
ALTER TABLE eg_pt_propertydetail_audit_v2
    ADD COLUMN IF NOT EXISTS structuretype VARCHAR(64);

ALTER TABLE eg_pt_address_audit_v2
    ADD COLUMN IF NOT EXISTS roadtype VARCHAR(64);
