-- #197 구버전 전환 전 수동 운영용. Flyway migration이나 자동 실행 파일이 아니다.
-- 승인/복구 백업을 확인하고 모든 신버전 쓰기를 중단한 유지보수 구간에서만 실행한다.
-- is_active/상품 설정은 그대로 보존하고, 구버전이 모르는 메타데이터만 LEGACY로 바꾼다.
-- 구버전 사용 후 재업그레이드해도 이 모드를 일괄 AUTO로 복구하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
LOCK TABLE public.warehouses, public.products IN ACCESS EXCLUSIVE MODE;
UPDATE public.products
SET activation_mode = 'LEGACY', auto_inactive_reason = NULL
WHERE activation_mode <> 'LEGACY' OR auto_inactive_reason IS NOT NULL;
COMMIT;
