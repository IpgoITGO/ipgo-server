-- Additive only. Existing external originals are not moved, rewritten or deleted.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_ai_service_reader') THEN
        RAISE EXCEPTION 'AI service reader is missing; run the approved role bootstrap first';
    END IF;
END $$;
ALTER TABLE upload_files ADD COLUMN storage_kind VARCHAR(20) NOT NULL DEFAULT 'EXTERNAL';
ALTER TABLE upload_files ADD CONSTRAINT ck_upload_storage_kind CHECK (storage_kind IN ('EXTERNAL', 'DATABASE'));
ALTER TABLE upload_files ADD CONSTRAINT ck_upload_database_key
    CHECK (storage_kind <> 'DATABASE' OR storage_key LIKE 'db/%');

CREATE TABLE upload_originals (
    upload_file_id BIGINT PRIMARY KEY REFERENCES upload_files(id) ON DELETE RESTRICT,
    content BYTEA NOT NULL,
    content_type VARCHAR(100) NOT NULL
        CHECK (content_type = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retain_until TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 days'),
    CONSTRAINT ck_upload_original_size CHECK (octet_length(content) = size_bytes),
    CONSTRAINT ck_upload_original_retention CHECK (retain_until >= created_at + INTERVAL '30 days')
);

-- No automatic cleanup. Even an administrative maintenance statement cannot shorten the first 30 days.
CREATE FUNCTION preserve_upload_original_retention() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        IF EXISTS (SELECT 1 FROM upload_originals WHERE retain_until > CURRENT_TIMESTAMP) THEN
            RAISE EXCEPTION 'Original is protected by its minimum retention period' USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;
    IF OLD.retain_until > CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'Original is protected by its minimum retention period' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_upload_original_retention BEFORE UPDATE OR DELETE ON upload_originals
FOR EACH ROW EXECUTE FUNCTION preserve_upload_original_retention();
CREATE TRIGGER trg_upload_original_retention_truncate BEFORE TRUNCATE ON upload_originals
FOR EACH STATEMENT EXECUTE FUNCTION preserve_upload_original_retention();

-- V13 default privileges grant Spring DML: remove mutation/deletion for preserved originals.
REVOKE ALL ON upload_originals FROM ipgo_server, ipgo_ai_reader, ipgo_ai_service_reader;
GRANT SELECT, INSERT ON upload_originals TO ipgo_server;

CREATE VIEW ai_upload_originals_v1 WITH (security_barrier = true) AS
SELECT uf.id AS upload_file_id, w.user_id, uf.warehouse_id, uf.storage_key, uf.original_name,
       uf.status, uf.file_hash AS sha256, o.content_type, o.size_bytes, o.content
FROM upload_originals o JOIN upload_files uf ON uf.id = o.upload_file_id
JOIN warehouses w ON w.id = uf.warehouse_id
WHERE uf.storage_kind = 'DATABASE';
REVOKE ALL ON ai_upload_originals_v1 FROM PUBLIC, ipgo_ai_reader;
DO $$ BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ipgo_ai_service_reader', current_database());
END $$;
GRANT USAGE ON SCHEMA public TO ipgo_ai_service_reader;
GRANT SELECT ON ai_product_daily_stocks_v1, ai_cleaning_sources_v1,
    ai_cleaning_products_v1, ai_cleaning_previous_units_v1, ai_upload_originals_v1
TO ipgo_ai_service_reader;
