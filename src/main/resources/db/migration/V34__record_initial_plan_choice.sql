-- #198 기존 회원은 이미 선택한 것으로 취급한다. 실제 선택 이력이 없는 기존 행의
-- created_at은 호환용 표시이며 새로운 체험을 부여하거나 기존 구독 기간을 바꾸지 않는다.
ALTER TABLE users ADD COLUMN plan_chosen_at TIMESTAMP WITH TIME ZONE;
UPDATE users SET plan_chosen_at = created_at;
-- 기본값을 두지 않아 migration 이후 가입한 회원은 선택 전까지 NULL이다.
