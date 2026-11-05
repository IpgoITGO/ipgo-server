# 0007. 현재 결과811로 전환하되698과 예산1회 공유

- 관련 Server113/109/107, IPGO-71. 사용자가 선택한 범위는 코드·검사·PR 준비이며 실제 배포/유료는 별도 승인한다.
- 실제 마스터 계정의698 입력/잔여1 사전 확인은 성공했다. 하지만 현재 상품 화면은811을 조회해698 생성은0회로 중단했고, 사용자가811 한 건 준비를 선택했다.
- 전체 자동 설명/worker는false 유지하고 단건 활성화/비용승인 기본false·기존 Bearer/쿠키/CSRF 경계를 유지한다.

## 실행 대상·읽기 사전 확인

대상은 코드에 고정된811이다. GET/POST 단건 경로의 다른ID(698 포함)는403이다.
GET `/api/results/811/insight/regeneration`은 소유권·원래 입력과 저장값·698/811 미사용 예산·현재
화면 결과를 읽기 확인한다. 성공은 resultId/productId/enabled/remainingAttempts1만 반환하며
표식/값/문장 생성은 없다. POST는 같은 소유자의 빈 본문만 허용하고 모든 검사를 다시 수행한다.

현재 결과 판정은 ProductDetailService.detail을 재사용한다. 같은 회원의 기본 창고에서 마지막
DONE run에 속한 해당 상품 결과가811이어야 한다. MAX(result.id)로 대체하거나, 새 run에 해당
상품이 없는데 과거 결과를 계속 현재로 보는 처리는 하지 않는다. 바뀌었으면409
INSIGHT_RESULT_STALE이고 다른ID로 자동 전환하지 않는다. 결과가 바뀐 화면을 위해 추가 호출하지 않는다.

## 기존 승인 예산을 공유하는 두 행

기준 예산 행698과 실행 행811을 모두 요구하고, 동일 소유자·창고 소유권을 확인한다.
둘 다 NONE/DISABLED/LLM_DISABLED·표식/문장/과금/토큰 이력 없음이어야 한다. 옛 legacy
validation_passed=true/DISABLED는 허용한다. 하나라도 이미 쓰였거나 결과가 불명확하면 재시도하지 않는다.
기준 행이 없어도 새 예산을 삽입하지 않는다.

POST는 두 unique 설명 행을698→811 순서로 FOR UPDATE 잠근다. 현재 화면 판정과 기존
StoredInsightFacts의 날짜/배수/계산식/NO_DATE/위험/시나리오 검증을 모두 통과한 뒤 다음을
하나의 REQUIRES_NEW transaction으로 commit한다.

- 698 output_path = single-budget/698-to-811/attempt-1. 계산/설명/status/created_at은 보존한다.
- 811 output_path = single-result/811/attempt-1, PROCESSING과 검증한 payload 저장.

둘 중 하나의 저장이 실패하면 모두 rollback되어 외부 HTTP는0회다. commit 뒤에는811의
InsightGeneration.processClaimed만 호출한다. 두 대상 합산 최대1회이며 ID 전환으로 새 한도를
추가하지 않는다. migration·한도 초기화·재무장 API는 없다.

구버전698 코드도 output_path IS NULL을 요구한다. 따라서 새811이 예산을 쓰면 rollback된
구버전의698 호출도 거부된다. 구버전698이 먼저 잠금/claim했다면 새811은 거부된다.
표식은 서버 재시작·호출자 rollback·timeout·응답저장 실패 후에도 유지한다. 이는 외부 모델
exactly-once 보장이 아니며 claim 직후 중단되면 실제 호출0회로 끝날 수 있다.

## 복구와 승인

단건 daemon은 만료된811의 신규 표식 또는698의 구버전 처리 표식만 기본 문장으로 마감한다.
698의 신규 생성을 시작하지 않고, 위임된698/DISABLED 행도 처리하지 않는다. HTTP는 호출하지 않는다.
새 버전이 rollback되면811 마감 코드가 없어지므로 PROCESSING이 남았다면 먼저 마감을 확인한다.
표식·예산 행을 삭제해 다시 호출하는 SQL은 제공하지 않는다.

승인할 때에는 최신 dev 포함 범위·고정 source/CI·직전 task·짧은 one-box 재시작·공유 표식2행/
811 설명 변경·전체 자동false를 확인한다. 활성화 후 실제 마스터 계정의 사전GET과 화면811을
다시 확인한 경우에만 유료POST 최대1회 → 저장GET/실제화면을 검증한다. 준비 단계에서는
실제 배포·운영DB 변경·유료 요청을 하지 않는다. 가짜 HTTP/LLM·폐기용 DB 검사와 실데이터/모델 결과를 구분한다.
