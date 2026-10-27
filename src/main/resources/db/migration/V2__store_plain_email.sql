-- 기존 암호문은 별도 복호화 이관 전까지 보존한다. 검사에 실패하면 이후 변경을 진행하지 않는다.
ALTER TABLE users ADD CONSTRAINT ck_users_email_decrypted CHECK (email NOT LIKE 'v1:%');
ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(254);
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);
ALTER TABLE users DROP CONSTRAINT uk_users_email_lookup;
ALTER TABLE users DROP COLUMN email_lookup_hash;
ALTER TABLE users DROP CONSTRAINT ck_users_email_decrypted;
