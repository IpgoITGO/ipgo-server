# 지속 인사이트의 월별 호출 상한

2026-11-06 후속 사용자 승인: 개발환경의 월 US$10 차단을 해제한다. 배포 입력 `unlimited_insight_approved=true`는 지속 모드·비용 승인과 함께 사용해야 하며 `IPGO_INSIGHT_MONTHLY_BUDGET_ENABLED=false`를 명시한다. 이 모드에서는 월 카운터를 읽거나 갱신하지 않고, 호출 원장의 dispatch 기록과 중복 방지만 유지한다. 기존 카운터와 V22는 삭제·초기화하지 않는다. 개별 요청의 모델·입출력·시간 제한은 유지한다. 이 앱의 월 비용 상한은 없으며 공급자 계정 자체 한도는 별개다. 아래 월 US$10 설명은 선택적으로 다시 활성화한 제한 모드에 해당한다.

2026-11-06 사용자 요청: 개발환경에서 LLM 지속 사용, 이 앱의 월 API 비용 US$10 이내.

모델은 `gpt-4o-mini-2024-07-18`, Standard(default), n1, 입력 UTF-8 메시지 바이트 수+1024를16,000 이하로 제한하고 출력400 이하·SDK retry0·redirect0을 적용한다. [공식 가격](https://developers.openai.com/api/docs/pricing)의 입력$0.15/출력$0.60/백만token 기준으로 조건부 요청 상한은$0.00264다. 호출마다$0.01을 보수 예약해 한국시간 달마다 전체 회원 합계 최대1,000번만 dispatch한다. 이 앱 밖의 같은 키 사용·세금·환전·공급자 가격 변경을 포함한 계정 청구 하드캡이 아니다.

V22 `insight_monthly_usage`는 업무 테이블과 FK가 없다. 계산·회원·호출 원장을 삭제해도 사용량이 줄지 않는다. PostgreSQL advisory transaction lock 아래에서 월 row를 잠그고, 호출 원장의 `dispatched_at`과 월 counter 증가를 같은 transaction으로 commit한 뒤 HTTP를 보낸다. 실패·시간초과·프로세스 중단·사전입력차단도 예약을 반환하지 않는다. 중복 attempt는 재전송하지 않는다. 월 상한이면 기본 문장으로 마감하고 계산은 보존한다.

실행 역할은 현재 월0카운터 생성·조회·1씩 증가만 할 수 있고 DELETE/차감/월 변경은 거부한다. DB hard ceiling도1,000이다. migration은 남아 있는 현재 월의 기존 dispatch도 보수 차감한다. 삭제된 과거 청구나 다른 서비스의 호출액을 재구성하는 회계 기능은 아니다. 월은 DB `clock_timestamp()`와 Asia/Seoul 기준으로 계산하고 첫 요청에서 새 월 row를 만든다. cron이나 자동 한도 충전 작업은 없다.

지속 모드는 deploy workflow에서 별도로 승인한다. 시작 시점 이후의 새 계산만 대상으로 하며 과거 DISABLED 결과나 고정 단건/배치 예외는 활성화하지 않는다. 기존 시간제한 모드는 유지한다. 새 월을 허용해도 과거 실패/예약 row를 고치지 않는다.

## 검증

- H2 월 한도·실패·재시작·중복·삭제 후 보존·한국시간 월 경계·동시성.
- 기존 설명 생성 통합: 한도0에서 HTTP0·기본문장·계산 보존 포함. 실제 공급자 호출을 하지 않는 검사다.
- `scripts/testing/verify-monthly-insight-budget.sh`: 외부 포트 없는 격리 PostgreSQL16에서 V22 DDL·역할/trigger, Java 실제 클래스 두 인스턴스가 남은2회를 동시 요청10건에 정확히2회 허용, 호출 삭제·객체 재생성 후 상한 유지·카운터 삭제/차감 거부를 검증한다. 로컬 fixture 예약998회는 유료 호출 실측이 아니다.
- 전체 Flyway1~22·역할 검사는 기존 절차에서 수행한다. 개발RDS는V15/V17이 보류되어 있으므로 일반 migrate는 사용하지 않고, 승인된 선택 목록(1~14,16,18~22)과 실제 checksum을 대조해V22만 추가한다.

## 적용·중단

외부 push/PR/병합·개발배포·V22 실제 적용·유료 호출은 이 로컬 구현 기록과 구별한다. 실행 전 정확 source SHA·환경·기존 작업 유휴·새 snapshot·부작용·복구를 사용자와 확정한다. 적용 순서는 선택V22→새Server guard/OFF→새AI 지속모드/key ON→같은Server SHA의 gate/worker ON이다. 이 단계에서는 현재 계산과 원장을 삭제/초기화하지 않는다.

중단 시 Server gate와 worker OFF를 우선하고 AI key 주입을 제거한다. 시작된 공급자 요청의 청구 취소를 보장하지 않는다. 유료 호출 이후에는 예전snapshot으로 counter를 되돌리지 않는다. 전체DB 복원이 필요하면 현 counter/호출 원장 보존·대사 계획을 별도 승인받는다. V22/누적 카운터는 남기며 구코드 복귀도 LLM OFF로만 한다. 승인된 무상한 모드에서도 중단·구코드 복귀 시에는 자동 설명을 OFF로 한다.
