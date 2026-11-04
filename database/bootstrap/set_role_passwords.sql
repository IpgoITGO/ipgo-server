\set ON_ERROR_STOP on

-- psql reads these values from the task environment. Do not replace them with literals.
\getenv ipgo_migrator_password IPGO_MIGRATOR_PASSWORD
\getenv ipgo_server_password IPGO_SERVER_PASSWORD
\getenv ipgo_ai_reader_password IPGO_AI_READER_PASSWORD

ALTER ROLE ipgo_migrator PASSWORD :'ipgo_migrator_password';
ALTER ROLE ipgo_server PASSWORD :'ipgo_server_password';
ALTER ROLE ipgo_ai_reader PASSWORD :'ipgo_ai_reader_password';
