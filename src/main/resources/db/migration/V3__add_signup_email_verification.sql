-- 기존 회원의 회사 정보는 임의로 채우지 않는다. 신규 가입의 필수값은 API에서 검증한다.
ALTER TABLE users ADD COLUMN company_name VARCHAR(50);
ALTER TABLE users ADD COLUMN manager_name VARCHAR(50);
ALTER TABLE users ADD COLUMN business_reg_no VARCHAR(10);

CREATE TABLE email_verifications (
    email VARCHAR(254) PRIMARY KEY,
    code_hash VARCHAR(255),
    code_expires_at TIMESTAMP WITH TIME ZONE,
    last_sent_at TIMESTAMP WITH TIME ZONE,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    token_hash VARCHAR(64),
    token_expires_at TIMESTAMP WITH TIME ZONE
);
