-- #172 회원 탈퇴. 탈퇴하면 개인정보를 지우고 탈퇴 시각을 남긴다. 비어 있으면 탈퇴하지 않은 회원이다.
-- 기존 행은 NULL로 남고, 되돌릴 때는 이 칸만 지우면 된다.
ALTER TABLE users ADD COLUMN withdrawn_at TIMESTAMP WITH TIME ZONE;
