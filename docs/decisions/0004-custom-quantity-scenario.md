# 0004. 직접 입력 수량 시나리오 — 원래 계산 입력·한 행 갱신

- 상태: 구현·로컬 회귀/서비스 간 통합 검증. 개발 배포·실제 개발 화면 검증은 후속
- 결정일: 2026-11-04 / 요구사항: K3·BE-09·DA-06
- 근거: [Server94](https://github.com/IpgoITGO/ipgo-server/issues/94),
  [시작·계약 기록](https://github.com/IpgoITGO/ipgo-server/issues/94#issuecomment-6632686671),
  [공통 계약25](https://github.com/IpgoITGO/ipgo-server/issues/25), Client78

## 입력·결과와 수량 정책

Client의 기존 `{qty}` POST·201·단일 CUSTOM 응답과 같은 결과 아래 한 행을 덮어쓰는 계약을 사용한다.
현재 화면의 입고 단위 올림 안내와 AI가 받은 qty를 그대로 계산하는 동작을 서버 adapter에서 연결한다.
qty는 양의 JSON 정수이며 원래 계산 snapshot의 입고 단위로 올린다. 기술적 상한은
NUMERIC(18,3) 저장 범위에 맞춘999,999,999,999,999이며 올림 뒤 초과도400으로 거절한다.

현재 Client를 바꿔 단위의 배수만 받는 방안이나 AI의 계산 정책을 바꾸는 방안 대신 서버에서 올린
정수를 전달한다. 수량이 동일한 기본 시나리오가 있으면 서버의 저장 결과 기준으로 isDuplicate를
정한다. 응답 표시의 내림/올림과 도착 후 미충족량은 K2와 동일하다.

## 원래 입력과 접근 제어

`calculation_runs.selection_snapshot.request`의 회원·창고·run·기준일·계산일·source·상품 설정을 확인한다.
클라이언트가 계산 입력을 보내도 사용하지 않는다. 응답 SKU만 productIds 한 개로 제한하고 전체
원래 settings·policy·source·plannedReceipts·noDateReceipts는 유지한다. AI의 전체 상품 기준 위험
표본은 productIds로 pool을 줄이지 않는 기존 구현을 따른다.

다른 회원은403, 없는 결과는404다. 최신 run의 DONE·활성 상품·OK 또는 NOT_NEEDED·현재 자료와
같은 최신 revision·기준일7일 이내를 요구한다. 이전 결과/원래 입력 누락/파일 처리 중·실패·새 계산
진행은409 SCENARIO_STALE이다. 실패 후 화면에 보존된 이전 결과에도 새 CUSTOM을 붙이지 않는다.

## 두 단계 transaction과 동시 처리

1. 짧은 읽기 transaction에서 원래 입력·기존 CUSTOM ID·같은 올림 수량 cache를 확인한다.
2. DB transaction 밖에서 실제 AI `/engine/scenarios`에 HTTP/1.1로 보낸다. Bearer·쿠키를 전달하지 않는다.
3. 응답 type/수량/숫자 범위/위험/null/기간·일별 projection을 검사한다.
4. 짧은 저장 transaction에서 warehouse→result 순서로 잠그고 최신 상태·source·원래 입력·CUSTOM ID를
   다시 확인한다. CUSTOM 삭제·신규 한 행 삽입은 같은 transaction이다. 실패하면 이전 행도 복구된다.

같은 올림 수량의 순차 요청은 저장 행·ID를 재사용한다. 같은 수량의 동시 계산은 이미 저장된 한 행을
반환한다. 서로 다른 수량이 경합하면 먼저 저장한 결과를 유지하고 뒤늦은 응답은409 SCENARIO_CHANGED다.
마지막 완료 응답이 다른 요청의 새 입력을 조용히 덮어쓰는 방식은 사용하지 않는다.

기본 추천·기본 시나리오·원래 snapshot·현재 설명은 수정하지 않는다. CUSTOM 설명 재생성·실제 LLM과
호출 이력은25/61/33의 별도 범위이며 이 POST에서 호출·재생성하지 않는다.

## 설정·오류·배포와 복구

IPGO_ENGINE_BASE_URL은 승인된 내부 AI origin만 설정한다. 경로·userinfo·query·fragment는 허용하지
않는다. 자동 redirect도 하지 않는다. 연결1초, 전체 요청/본문 수신 기본10초(IPGO_SCENARIO_TIMEOUT),
본문512KiB 제한을 적용한다. 미설정/엔진 비활성/AI 사용 중은503 ENGINE_UNAVAILABLE,
시간초과는504 ENGINE_TIMEOUT, 잘못된 응답은502 ENGINE_ERROR다. 응답·입력·토큰 원문을 오류에 싣지 않는다.
저장 장애는503 SCENARIO_SAVE_FAILED이며 성공한 기본 결과와 이전 CUSTOM은 보존한다.

기존 V11 UNIQUE(result_id,scenario_type)와 V14 snapshot을 사용하며 새 migration은 없다.
76·PR77·AI60·V15의 DB 원본 전환 보류는 유지한다. 새 설정은 문서와 application.yaml 이름만 추가했고
현재 개발 ECS/Secret/배포 workflow 값은 변경하지 않았다. 개발 반영 때 실제 내부 DNS·설정을 확인하고
배포 후 Client의 직접 수량 입력→응답→표시를 실제로 확인해야94 전체 완료로 판단한다.

앱 원복 시 이전 이미지로 돌아가고 CUSTOM 데이터/schema·기본 결과를 보존한다. 이전 K2도 CUSTOM을
조회할 수 있다. 단지 한 행 갱신이라는 특성상 지난 CUSTOM 수량의 별도 이력은 이 기능에서 만들지 않는다.

## 검증 범위

- CustomScenarioIntegrationTests: 실제 로컬 HTTP 수신기를 사용한 Spring 인증/입력/소유권·수량 올림·
  cache·갱신·원본 보존·오류/본문 timeout/크기·DB rollback·동시 요청·stale 검증. 수신기 숫자는 합성이다.
- CustomScenarioRealAiIntegrationTests: 명시적 opt-in. loopback의 빈 scenario_test PostgreSQL과 실제
  FastAPI 수치 엔진을 연결한다. 기존 SQL에서 조회 view만 준비하고 test reader로 합성31일/1상품을 읽는다.
  2026-10월/11월4일로 고정한 합성 회귀이며 실제 사용자 원본·현재 운영 부하·LLM 평가가 아니다.
- 일반 검사는 `./gradlew test bootJar`. opt-in은 격리 PG·실제 로컬 AI를 준비하고
  IPGO_TEST_DB_URL/USERNAME/PASSWORD 및 IPGO_SCENARIO_REAL_AI_URL을 주입한 뒤
  `./gradlew test --tests '*CustomScenarioRealAiIntegrationTests'`로 실행한다. 안전 guard가 있는 loopback
  scenario_test DB만 쓰며 이 테스트는 행을 만들고 삭제하므로 실제 업무 DB에 적용하지 않는다.

테스트 통과·코드 게시·개발 배포·실제 화면 완료를 구분하고 최초 실패 원장을 후속 성공으로 바꾸지 않는다.
