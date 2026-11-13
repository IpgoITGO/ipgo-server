-- #206 가입 동의 기록과 무료 체험 재사용 막기.
-- 기존 행은 바뀌지 않는다(새 칸은 비어 있거나 FALSE). 되돌릴 때는 새 칸 4개와 새 표 1개만 지우면 된다.

-- 회원이 동의한 약관 · 개인정보 수집 · 이용 동의서의 버전과 시각. 비어 있으면 동의 기록이 없는 기존 계정이다.
ALTER TABLE users ADD COLUMN agreement_version VARCHAR(40);
ALTER TABLE users ADD COLUMN agreed_at TIMESTAMP WITH TIME ZONE;

-- 탈퇴한 회원 이메일의 확인값(HMAC). 원래 이메일로 되돌릴 수 없고, 탈퇴일로부터 1년 뒤 지운다.
-- 같은 이메일로 1년 안에 다시 가입하면 무료 체험 없이 시작한다.
CREATE TABLE trial_reuse_fingerprints (
    email_fingerprint CHAR(64) PRIMARY KEY,
    withdrawn_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (expires_at > withdrawn_at)
);
CREATE INDEX idx_trial_reuse_fingerprints_expires ON trial_reuse_fingerprints (expires_at);

-- 가입한 순간에 확인값이 있었는지. 가입 때 한 번 정하고 바꾸지 않는다.
-- 나중에 확인값이 만료 · 삭제돼도 첫 구독 조회 시점에 따라 판정이 바뀌지 않게 한다.
ALTER TABLE users ADD COLUMN trial_reused BOOLEAN NOT NULL DEFAULT FALSE;

-- 체험 없이 시작한 구독인지. 화면에 일반 체험 만료와 다른 안내(TRIAL_ALREADY_USED)를 보여 준다.
ALTER TABLE billing_subscriptions ADD COLUMN trial_reused BOOLEAN NOT NULL DEFAULT FALSE;

-- PostgreSQL 전용
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_reader') THEN
        REVOKE ALL ON trial_reuse_fingerprints FROM ipgo_ai_reader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ipgo_ai_service_reader') THEN
        REVOKE ALL ON trial_reuse_fingerprints FROM ipgo_ai_service_reader;
    END IF;
END $$;
