CREATE TABLE auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);

-- 사용된 해시도 보관해 이전 토큰 재사용 시 해당 로그인 전체를 폐기한다.
CREATE TABLE refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES auth_sessions (id) ON DELETE CASCADE,
    used_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);
