-- Add zone, road_type, and structure_type columns to eg_pt_billingslab_v2
-- Migration for Jagdalpur PT billing slab configuration
-- These columns allow separate filtering instead of compound areatype codes

-- Add new columns
ALTER TABLE eg_pt_billingslab_v2 
ADD COLUMN IF NOT EXISTS zone VARCHAR(10);

ALTER TABLE eg_pt_billingslab_v2 
ADD COLUMN IF NOT EXISTS road_type VARCHAR(64);

ALTER TABLE eg_pt_billingslab_v2 
ADD COLUMN IF NOT EXISTS structure_type VARCHAR(64);

-- Create indexes for efficient filtering
CREATE INDEX IF NOT EXISTS index_eg_pt_billingslab_v2_zone 
ON eg_pt_billingslab_v2 (zone);

CREATE INDEX IF NOT EXISTS index_eg_pt_billingslab_v2_road_type 
ON eg_pt_billingslab_v2 (road_type);

CREATE INDEX IF NOT EXISTS index_eg_pt_billingslab_v2_structure_type 
ON eg_pt_billingslab_v2 (structure_type);

-- Composite index for common query pattern (zone + road_type + structure_type)
CREATE INDEX IF NOT EXISTS index_eg_pt_billingslab_v2_zone_road_struct 
ON eg_pt_billingslab_v2 (zone, road_type, structure_type);

-- Composite index including tenant for multi-tenant queries
CREATE INDEX IF NOT EXISTS index_eg_pt_billingslab_v2_tenant_zone_road_struct 
ON eg_pt_billingslab_v2 (tenantid, zone, road_type, structure_type);
