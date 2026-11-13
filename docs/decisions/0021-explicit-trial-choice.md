# 체험 선택 시점과 최초 요금제 선택 기록 (#198 / IPGO-228)

상태: 로컬 구현 계약. 실제 DB 적용·개발 배포·Client 연동·실제 결제 검증과 구분한다.

## API와 상태

- 구독 응답에 nullable `planChosenAt`을 추가한다. 신규 계정은 `null`이며 조회·로그인·결제 주문 생성·가상계좌 발급만으로 선택을 기록하지 않는다.
- 체험 가능한 신규 미선택 계정은 `status=NOT_SELECTED`, `trialEnd=null`, `entitlements.reason=PLAN_NOT_SELECTED`와 쓰기/계산/인사이트 권한 false를 반환한다. 저장된 `trial_end`의 가입 시각은 내부 placeholder이며 체험 부여가 아니다.
- `POST /api/billing/trial/start`: Bearer 인증과 `Idempotency-Key`(영숫자/밑줄/하이픈 8~100자)가 필요하고 본문은 없다. 응답은 구독 DTO, 200/no-store다. 회원 행 잠금 안에서 최초 선택 시각을 서버 Clock으로 정하고, 그 시각+14일을 체험 종료로 저장한다. 다음 로그인·기기에서도 같은 값을 조회한다.
- 동일 키 재시도와 다른 키의 반복 선택은 기간을 연장하지 않는다. 응답은 현재 구독 상태이며 이미 선택한 기존/유료/만료 계정에는 새 체험을 부여하지 않는다. `TRIAL_START` 명령과 선택 시각·기간은 같은 transaction에 기록한다.
- #206의 재가입 제한은 유지한다. `trial_reused=true` 계정은 기존 `TRIAL_EXPIRED`/`TRIAL_ALREADY_USED` 응답을 유지하며, 미선택 재가입 계정의 `trial/start`는409/TRIAL_ALREADY_USED로 거절한다. 이미 선택한 기존 계정은 기간을 바꾸지 않고 현재 상태를 반환한다. 탈퇴 계정은409/ACCOUNT_WITHDRAWN이며, 선택 전 취소 등 체험을 시작할 수 없는 상태는409/TRIAL_NOT_AVAILABLE이다.
- 일반결제 카드 승인·가상계좌 입금 및 기존 정기결제의 검증된 승인 원장을 반영할 때 선택 시각이 비어 있으면 provider의 approvedAt을 같은 transaction에 기록한다. UNKNOWN/실패/미입금/발급 취소는 기록하지 않는다. 체험 후 유료 전환 등 이미 선택한 계정의 최초 시각은 유지한다. 환불·만료로 이 값을 지우지 않는다.

## V34와 기존 계정

`users.plan_chosen_at TIMESTAMPTZ`를 추가하고 migration 시점의 모든 기존 계정에 `created_at`을 채운다. 이 값은 기존 계정을 다시 묻지 않기 위한 호환 표시로, 실제 선택/동의 시각을 측정한 이력이 아니다. 신규 기본값은 NULL이다. 기존 `billing_subscriptions`·체험 종료·결제 기간·원장에는 변경을 가하지 않는다. 구독을 아직 조회하지 않은 기존 계정도 가입일+14일이라는 기존 계산을 유지한다. 기존 체험 만료자를 새 체험으로 바꾸지 않는다.

실제 적용 전에는 최신 migration 순서·회원 backfill 영향·백업을 확인하고 별도 승인받는다. 코드 복구 시 선택 시각과 원장을 보존한다. 구버전의 가입일 기준 체험 동작을 그대로 복구하면 신규 정책과 달라지므로 신규 선택/결제를 중단하고 데이터와 Client 계약을 대조한 뒤 승인된 버전으로 복구한다. 자동 down migration·선택 시각 삭제·기존 체험 기간 재설정은 하지 않는다.

## Client와 적용 조건

기존 Client의 PlanGate는 localStorage와 TRIALING에 의존하며 이 endpoint를 호출하지 않는다. Client IPGO-227에서 `planChosenAt=null`을 기준으로 선택 화면을 표시하고 체험 확인 때 `trial/start`를 호출하도록 연결해야 한다. 브라우저 선택 기록만으로 새 서버 권한을 얻을 수 없다. 신규 NOT_SELECTED/PLAN_NOT_SELECTED 처리와 재가입 제한 안내도 필요하다.

따라서 이 Server를 기존 Client에 단독 배포하지 않는다. Client와 migration/API 계약을 함께 확인한 승인된 적용 단위가 필요하다. 이번 작업은 Server의 로컬 구현·회귀·폐기 PostgreSQL 검증까지이며 실사용 검증은 아니다.
