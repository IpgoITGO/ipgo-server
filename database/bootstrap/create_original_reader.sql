\set ON_ERROR_STOP on
\getenv ipgo_ai_service_reader_password IPGO_AI_SERVICE_READER_PASSWORD

-- Upgrade only. Do not reset or touch any existing runtime/migration credentials.
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ipgo_ai_service_reader') THEN
        RAISE EXCEPTION 'AI service reader already exists; inspect it instead of rotating its password';
    END IF;
END $$;
CREATE ROLE ipgo_ai_service_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT
    PASSWORD :'ipgo_ai_service_reader_password';
ALTER ROLE ipgo_ai_service_reader SET default_transaction_read_only = on;
COMMIT;
