-- Additive only. Existing external originals are not moved, rewritten or deleted.
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
    CONSTRAINT ck_upload_original_size CHECK (octet_length(content) = size_bytes)
);

-- V13 default privileges grant Spring DML: remove mutation/deletion for preserved originals.
REVOKE ALL ON upload_originals FROM ipgo_server, ipgo_ai_reader;
GRANT SELECT, INSERT ON upload_originals TO ipgo_server;

CREATE VIEW ai_upload_originals_v1 WITH (security_barrier = true) AS
SELECT uf.id AS upload_file_id, w.user_id, uf.warehouse_id, uf.storage_key, uf.original_name,
       uf.status, uf.file_hash AS sha256, o.content_type, o.size_bytes, o.content
FROM upload_originals o JOIN upload_files uf ON uf.id = o.upload_file_id
JOIN warehouses w ON w.id = uf.warehouse_id
WHERE uf.storage_kind = 'DATABASE';
GRANT SELECT ON ai_upload_originals_v1 TO ipgo_ai_reader;
