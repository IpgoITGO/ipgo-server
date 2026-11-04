# 0001. AWS 데이터 접근과 계산 엔진 연동 계약

- 상태: 현재 구현 반영. 실제 LLM·직접 입력 시나리오·백테스트의 서버 연결은 별도 후속
- 결정일: 2026-10-29 / 구현 대조일: 2026-11-04
- 관련 Issue: [Server #26](https://github.com/IpgoITGO/ipgo-server/issues/26),
  [Server #27](https://github.com/IpgoITGO/ipgo-server/issues/27),
  [AI #15](https://github.com/IpgoITGO/ipgo-ai/issues/15)

## 목적과 현재 범위

Spring Boot는 업무 데이터와 상태 변경을 맡고 FastAPI는 정제·계산을 수행합니다.
긴 작업은 SQS로 전달하고, 결과 검증·저장과 회원별 API 접근 제어는 Spring이 담당합니다.
이 문서는 역할과 경계를 설명합니다. 메시지·저장·재시도의 상세 기준은
[실제 정제·계산 연결](0003-engine-job-connection.md)을 함께 확인합니다.

| 작업 | 현재 구현 | 남은 범위 |
|---|---|---|
| 수불부 정제 | Spring outbox → SQS `CLEAN` → AI worker → 결과 큐 → Spring 저장 | 입고 자동 매칭 등 #26의 개별 완료 조건 |
| 입고 계산 | 같은 경로의 `CALCULATE`. 조건 저장·발주 변경·정제 완료 후 선행 조건을 확인해 요청 | 실제 업무 효과·장시간 안정성 검증 |
| 시나리오 비교 | 계산 때 저장한 80%·100%·120% 결과를 K2가 조회 | AI의 직접 입력 시나리오 HTTP 기능을 Spring에 연결하는 작업 |
| 인사이트 | AI 계산 결과와 함께 받은 설명 상태를 Spring이 저장하고 K4가 조회 | 실제 LLM 활성화·실패 시 대체 문장·화면 공동 검증 |
| 백테스트 | AI의 HTTP/worker 경로는 별도 구현. 현재 Spring에서 `BACKTEST` job을 생성·저장하는 연결은 없음 | 서버 작업 생성·저장·화면 연동 |

기존의 ‘짧은 시나리오·인사이트는 내부 HTTP로 호출’ 계획을 현재 Spring 구현 완료로 해석하지
않습니다. 현재 `ScenarioController`는 저장된 비교표 조회를 제공하고, K4도 내부 HTTP를 직접
호출하지 않습니다. AI endpoint의 존재와 서버·화면 연결 완료는 각각 확인합니다.

## DB 역할과 조회 경계

| 역할 | 허용 범위 | 제한 |
|---|---|---|
| `ipgo_migrator` | 승인된 Flyway schema 변경 | 앱 runtime 자격 증명으로 사용하지 않음 |
| `ipgo_server` | 업무 table DML·결과 저장·상태 변경 | runtime Flyway 비활성, 임의 schema 변경 권한 없음 |
| `ipgo_ai_reader` | 승인된 view의 SELECT | 원본 업무 table DML·DDL 금지, 읽기 전용 연결 사용 |

V13의 `ai_product_daily_stocks_v1`은 계산용 일별 재고를 제공합니다. V14는 정제 입력 확인용
`ai_cleaning_sources_v1`·`ai_cleaning_products_v1`·`ai_cleaning_previous_units_v1`과
`engine_jobs`, 계산 입력 hash를 추가합니다. 현재 migration 기준은 **V1~V14**입니다.
역할 bootstrap·권한과 복구 기준은 [Database ERD](../database/erd-v1.md) 및
[V13](../../src/main/resources/db/migration/V13__create_ai_reader_view.sql),
[V14](../../src/main/resources/db/migration/V14__create_engine_jobs_and_cleaning_views.sql)를 확인합니다.

조회 view 자체가 회원마다 분리된 자격 증명을 제공하는 것은 아닙니다. AI 애플리케이션은 요청의
회원·창고·업로드 식별자와 상태·원본 key를 view와 대조하고, 계산에서는 지정한 source만 읽습니다.
reader 자격 증명을 가진 직접 SQL 접근자의 범위도 고려해 비밀값 배포와 접근 권한을 관리합니다.

## 원본 파일과 source 선택

- 개발 AWS 원본은 private S3에 보관합니다. Spring은 원본 읽기·쓰기, AI는 읽기 역할을 사용합니다.
  로컬 개발은 기존 local storage adapter를 사용할 수 있습니다.
- 정제 한 건은 파일 하나입니다. Spring이 보낸 원본 식별자·key와 소유권을 확인한 뒤 같은 원본을
  읽습니다. 원본 보관과 DB transaction은 하나의 원자적 작업이 아니므로 DB 기록 실패 시 이미
  보관한 파일이 남을 수 있으며 임의 삭제하지 않습니다.
- 전월 연결은 직전 월의 최신 revision이 DONE일 때만 사용합니다. 최신 파일이 처리 중이거나
  실패했으면 예전 성공으로 대체하지 않고, 필요한 전월 말 기록이 없으면 명시적으로 실패합니다.
- 계산 source는 Spring이 기간별 최신 revision에서 선택합니다. AI가 임의로 다른 최신 파일을
  고르지 않습니다. 회원·창고·선택 파일의 일치 여부를 다시 확인합니다.
- 꺼진 상품과 검산 제외 상품은 입력 선택 정책을 따릅니다. 날짜 없는 발주는 `noDateReceipts`로
  전달해 설명에만 쓰고 미래 입고량에서는 제외합니다. 추가·취소는 계산 입력 hash에 반영합니다.

## 작업·상태·중복 처리

업로드·조건·발주 변경과 outbox 기록은 같은 DB transaction에 저장합니다. transaction 밖에서
작업을 보내며 전송 직후 중단돼도 같은 `jobId`를 재사용합니다. 결과는 식별자·소유권·입력
snapshot·상품 집합과 수치를 검증한 뒤 Spring transaction으로 저장하고, commit 후 ACK합니다.
오류나 중복 전달 때문에 과거 성공 결과를 삭제하지 않습니다. 재시도·lease·만료·DLQ 상세는
[엔진 작업 연결](0003-engine-job-connection.md)을 따릅니다.

`runId`는 선행 조건이 없거나 엔진이 꺼져 있으면 null일 수 있습니다. 최근 실행의 입력 hash가
같고 QUEUED/RUNNING/DONE인 경우에만 재사용합니다. 예전 값으로 되돌리는 변경·최근 실패·명시적
재계산은 새 실행을 만들며, 과거 성공 실행을 최신 실행으로 되살리지 않습니다.

큰 결과는 길이·해시·해제 상한을 검사하는 gzip/base64 v2를 지원합니다. 작은 v1 결과와
commit 후 ACK 원칙을 유지하며 [압축 계약](001-engine-result-compression.md)을 따릅니다.

## 검증 범위와 보류

2026-11-04 개발 환경은 Server [PR87](https://github.com/IpgoITGO/ipgo-server/pull/87),
AI [PR67](https://github.com/IpgoITGO/ipgo-ai/pull/67) 반영 후 동일한 두 달 수불부의 실제 화면
업로드·정제·계산·상품 상세를 확인했습니다. 날짜 없는 발주 등록·취소와 재계산도 확인했습니다.
관련 [Server 배포](https://github.com/IpgoITGO/ipgo-server/actions/runs/41831012328)·
[AI 배포](https://github.com/IpgoITGO/ipgo-ai/actions/runs/41831006988)의 실행 SHA를 기준으로 합니다.

실제 수치 엔진과 유료 LLM은 별개입니다. LLM 비활성으로 완료한 정상 계산은
`NONE/LLM_DISABLED`를 저장하고 K4는 `TABLE_ONLY`를 반환합니다. 실제 모델 품질·사람 채점 일치·
업무 효과·장시간 부하·전체 장애 복구를 이번 화면 시험의 성공으로 간주하지 않습니다.

**DB 원본 저장 전환은 보류입니다.** Server [PR77](https://github.com/IpgoITGO/ipgo-server/pull/77)·
AI [PR60](https://github.com/IpgoITGO/ipgo-ai/pull/60)·V15는 현재 구현에 포함하지 않습니다.
보관량·접근 권한·배포 순서·비용·복구 목표를 결정하고 별도로 승인받기 전에는 병합·migration·
모드 활성화·기존 S3/local 원본 이전이나 삭제를 수행하지 않습니다.

이 문서 변경은 코드·API·DB schema·권한·AWS 자원·배포 설정을 변경하지 않습니다.
