-- Add structureType to eg_pt_property (Property Registry v1 table)
-- This stores property-level structure/construction type for billing slab matching

ALTER TABLE eg_pt_property
    ADD COLUMN IF NOT EXISTS structuretype VARCHAR(64);
