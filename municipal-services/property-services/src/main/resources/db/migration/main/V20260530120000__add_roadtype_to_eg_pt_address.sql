-- Add roadType to eg_pt_address (Property Registry v1 table)
-- This is for Property Registry which uses eg_pt_address (not eg_pt_address_v2)

ALTER TABLE eg_pt_address
    ADD COLUMN IF NOT EXISTS roadtype VARCHAR(64);

-- Note: structureType maps to eg_pt_unit.constructiontype which already exists
