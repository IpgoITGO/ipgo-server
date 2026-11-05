# 현재 마스터 계정 후보 5건의 일회성 설명 생성

이 문서는6fbb792 배포의 기존812~816 계약을 보존한다. 현재1921~1925 전환과 구버전 호환 공유 예산은
[0008](0008-shared-insight-batch-budget.md)을 따른다.

관련 Issue #115. 2026-11-05 실제 읽기 확인에서 마스터 계정의 현재 상품은 31개가 아니라8개였다.
사용자가 결과812~816 5건 고정을 선택했다. 상품33~37/소유자1이며, 이미 생성한811,
계산 제외817, 결과 없는 상품39 및 다른 계정은 포함하지 않는다. 실제 값·상품명·키는 이 문서에 넣지 않는다.

## 고정 범위와 총 한도

`InsightBatchManifest`가 batch ID `master-current-20261105`, 소유자1, 상품/결과의 다섯 쌍,
총 최대5회를 코드에 고정한다. 요청·환경 변수로 대상/소유자/한도를 바꾸거나 현재 상품을 자동 추가할 수 없다.
최신 결과가 바뀌면 대체 ID를 선택하지 않는다. 이미 생성했거나 입력이 부족한 결과는 건너뛴다.

새 예산 테이블이나 migration 대신 기존 `ai_explanations`의 다섯 행이 영속 예산이다.
한 결과를 한 번만 예약할 수 있으므로 전체 HTTP 시도 수도 최대5다. 미생성·미사용을 엄격히 확인하고,
다섯 행을 결과ID 순서로 잠근 transaction에서 한 행의 `PROCESSING`, 입력,
`batch/master-current-20261105/{resultId}/attempt-1` 표식을 함께 commit한다.
그 후에만 FastAPI HTTP를 한 번 호출한다. 예산 행이 빠져도 다시 만들지 않는다.
실제 HTTP가 전송되기 전 프로세스가 중단돼도 예약은 소비한 것으로 처리한다.

중복·동시 요청·새 인스턴스·호출자 transaction rollback으로 예산이 되살아나지 않는다.
다른 한 건이 PROCESSING이면 다음 실행은 거부한다. 실패/검사 탈락/시간 초과/저장 실패/불명확 결과는
나머지 대상도 중단한다. 복구는 timeout+30초가 지난 자기 표식의 PROCESSING만 기본 문장으로 마감하며
LLM을 호출하지 않는다. 다음 실행이 허용되는 것은 이전 시도가 검사를 통과한 LLM/DONE인 경우뿐이다.
표식·설명 행의 수동 삭제나 복원은 승인 범위에 없으며, 이를 하면 한도 보장이 깨지므로 금지한다.

## API와 활성화

- `GET /api/insight-batches/master-current-20261105`: 정상 소유자 인증으로 enabled/max5/예약 수/
  현재 가능한 수/gate/각 고정 대상 상태를 읽는다. 비활성 상태에서도 읽을 수 있고 DB 쓰기·LLM 호출은 없다.
- `POST /api/insight-batches/master-current-20261105/results/{id}/generate`: 고정ID 하나에 빈 본문만 허용한다.
  ID/입력/횟수/한도 본문을 허용하지 않는다. 결과의 소유권·고정 상품·현재 화면·저장 사실·미사용을
  POST transaction 안에서 다시 확인한다. 반환 형식은 기존 insight GET과 같다.
- 잘못된 소유자/batch/ID403, 본문400, 비활성503 `INSIGHT_BATCH_DISABLED`,
  사용한 대상409 `INSIGHT_ATTEMPT_SPENT`, 나머지 중단409 `INSIGHT_BATCH_STOPPED`.
  입력 부족409 `INSIGHT_INPUT_UNAVAILABLE`, 현재 결과 변경409 `INSIGHT_RESULT_STALE`.

`IPGO_INSIGHT_BATCH_ENABLED` 기본false. workflow의 `batch_insight_enabled/approved`도 기본false.
이 경로만 승인할 때 batch 두 입력true, `single_result_insight_enabled=false`,
`insight_enabled/approved/worker_enabled=false`가 필요하다. 앱 시작과 배포 렌더러가 동시 활성화를 거부한다.
상시 작업기나 계산 요청으로는 배치가 시작되지 않는다. 활성화 배포 자체도 생성 호출을 하지 않는다.
기존811 문장·698 공유 표식과 정상 insight GET 계약은 변경하지 않는다.

## 검증·실행 승인·복구

테스트는 격리 DB와 로컬 HTTP/내부 FastAPI FakeClient로 수행한다. 실제 키·외부 유료LLM은 사용하지 않는다.
검증 결과와 실제 운영 대상의 읽기 확인은 PR에서 구분한다. 준비 중에는 실제 DB 쓰기·배포·유료 생성이 없다.

향후 실행은 별도 승인 후 정확한 SHA를 배포하고, 실제 플래그/헬스/정상 마스터 로그인/GET 사전 확인을
다시 수행한다. READY 고정 대상만 하나씩 요청하고 각 저장 GET·한도·실제 화면을 확인한다.
실패나 응답 불명확 시 다음 대상도 호출하지 않는다. 로컬 요청 기록을 호출 전에 배타 생성·보존하며,
HTTP 재전송이 없는 실행기를 사용한다. 공급자 비용은 예약 수와 구분해 실제 관측 범위만 기록한다.

복구 지점은 준비 시점 Server TD16/a68904a. 별도 승인 아래 batch/자동/worker를false로 배포하거나
직전 이미지로 복귀한다. PROCESSING이 있다면 배치 복구가 기본 문장으로 마감한 것을 확인한 뒤 되돌린다.
직전 버전에는 배치 복구기가 없으므로 미마감 롤백은 GET202가 남을 수 있다.
예산 표식·811/698·기존 결과를 삭제하거나 초기화하지 않는다. schema 변경·AI 재배포는 필요 없다.
