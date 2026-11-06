# LLM 호출 메타데이터와 이력 보존 — Server #33

기준: Server dev `ee15df8`, AI의 같은 작업 브랜치. 구현·로컬 검증과 실제 유료 호출·개발 배포 검증을 구분한다.

## 저장 계약

AI `/engine/insight` 응답에 nullable `modelName`, `inputTokens`, `outputTokens`를 추가한다.
모델은 제공사 응답 이름이며 요청 설정 이름으로 대체하지 않는다. 토큰은 제공사 usage 원값이다.
`costUsd`는 기존 호출기의 단가표로 계산한 추정치이며 청구액이 아니다. usage 미수신·단가 미확인·호출 오류는 null이다.
관측된 0과 미확인을 구분한다. 과거 null/0을 새 측정값으로 바꾸거나 backfill하지 않는다.
AI 문장 검사가 실패했어도 수신·검증한 모델·토큰·비용·시간은 보존한다.

서버는 이전 AI 응답(추가 필드 없음)도 수용한다. 그 응답의 0원은 과거 계약에서 미확인을 포함하므로, 모델·토큰이 모두 누락된 0원을 확인된 무료 호출로 집계하지 않는다. 음수·문자열/boolean 토큰·int 범위 초과·너무 긴 모델명·비정상 비용은
유효한 관측으로 저장하지 않고 기본 문장으로 대체한다. 공개 K4 DTO에는 모델·토큰·비용·원문을 추가하지 않는다.

## 호출과 보존

- `ai_explanations`는 기존의 계산 결과당 최신 설명 한 행을 유지한다.
  이력의 DONE은 종료를 뜻하며 성공 판정은 mode=LLM·validation_passed를 함께 확인한다.
- V17의 `ai_explanation_calls`는 호출 예약마다 별도 UUID를 만들고 요청·계산 result 연결·시작/종료·dispatch 시각·결과·검증·메타데이터를 보존한다.
- 새 자동/단건/배치 경로는 예약 상태와 이력을 같은 트랜잭션으로 commit한 뒤 HTTP를 호출한다. 이력 저장 실패 시 호출하지 않는다.
- `LIVE`는 새 코드의 예약, `LEGACY`는 이전 예약 경로를 새 생성기가 이어받은 경우, `RECOVERY`는 이전 배포의 이력 없는 PROCESSING을 호출 없이 만료 처리한 기록이다.
  예약·dispatch 시각은 제공사 수신/과금의 증거가 아니다. RECOVERY를 실제 호출·0원 성공 표본으로 세지 않는다.
- `latency_ms`는 AI 응답 관측값, `round_trip_ms`는 Spring→AI HTTP 왕복 관측값이다. 복구로만 종료하면 둘 다 미확인일 수 있다.
- 최신 설명·이력 종료 갱신은 한 트랜잭션이다. 현재 attempt UUID·PROCESSING 조건이 맞을 때만 종료한다.
  종료된 호출 행은 애플리케이션에서 다시 갱신하지 않는다. 복구 후 늦은 응답은 최신 설명과 종료 이력을 덮어쓰지 않는다.
- 기존 호출 예산·기본 OFF·재시도 금지는 유지한다. 같은 결과의 다회 생성은 저장 구조로 지원하되 공개 재생성 기능을 확대하지 않는다.
- 새 계산·업로드 덮어쓰기·최신 설명 변경은 과거 호출 행을 갱신하지 않는다. 계산 입력·업로드 파일·엔진 버전은 기존 run/result/source 연결로 추적한다.
- 자동 만료·정리 작업을 추가하지 않는다. 부모 calculation_result를 명시적으로 삭제하면 기존 설명과 같이 이력도 CASCADE 삭제된다.
  계정/원본 삭제·장기 보관 기간은 이 변경으로 새로 승인하거나 결정하지 않는다. DB 관리자는 기존 권한으로 수정할 수 있어 변조 방지 원장은 아니다.
- 과거 완료 행은 새 호출로 복원하지 않는다. timeout/통신 단절 뒤 실제 제공사 usage가 오지 않으면 비용은 미확인으로 남긴다.

## 반영과 되돌리기

기존 V1~16은 수정하지 않고 V17을 추가한다. 실행 중인 DB에 이번 작업으로 migration을 적용하지 않는다.
반영 시 V17 migration을 별도 승인·적용·검증한 뒤 서버(이전 응답 호환), AI(nullable 비용·새 메타데이터) 순서로 배포한다. 기존 환경의 Flyway OFF에서는 코드 배포만으로 schema가 생긴다고 가정하지 않는다.
이전 서버는 null 비용을 거부하므로 새 AI만 먼저 배포하지 않는다. dev에 다른 V17이 합쳐지면 승인 전에 번호를 재조정하고 재검증한다.
서버 롤백 시 AI도 기존 응답으로 되돌린다. 추가 테이블/열은 남겨 이력을 보존하며 DROP으로 복구하지 않는다.
과거 서버에서 이루어진 호출은 새 기록이 없거나 이미 예약된 호출의 종료 이력이 미완료일 수 있으므로 롤백 구간을 따로 표시하고 실제 호출 횟수로 추정하지 않는다.

## 검증

현재 작업의 최종 실행 결과·최초 실패·검증 한계는 상위 작업 공간의 `docs/2026-11-06-server33-history.md`에 기록한다.
합성 HTTP/제공사 fixture와 실제 유료 호출을 분리한다. H2/격리 PostgreSQL 회귀, 전체 migration/역할 검사, 실제 로컬 FastAPI 연동을 사용한다.

AI 브랜치에서 `.venv/bin/python -m tests.insight_history_fixture`를 실행하면 127.0.0.1:18033의 고정 합성 제공사를 사용한다.
같은 호스트의 서버 브랜치에서 `IPGO_INSIGHT_HISTORY_LOCAL_FASTAPI=true ./gradlew test --tests '*InsightGenerationIntegrationTests.localFastApiProviderFixtureReachesStoredHistoryAndPublicK4'`로
Spring 계산 결과→실제 FastAPI 생성/검사→메타데이터→Spring 이력/설명 조회를 확인한다. API key·DB·AWS가 없는 fixture다.

### 2026-11-06 로컬 실행 결과

- Server H2: 115개 통과·기존 별도 네트워크 선택 검사 3개 제외. 새 loopback FastAPI 계약 검사는 실행·통과했다.
- Server PostgreSQL 16: 같은 회귀에 계산 run API 검사를 더해 119개 통과·기존 선택 검사 3개 제외.
  이 회귀 harness는 V1~11 Flyway + portable V16/V17을 사용한다. V12~15의 실제 PostgreSQL 전용 역할/trigger는 다음 독립 검사로 검증했다.
- `scripts/verify-postgres-migrations.sh`: 격리 PostgreSQL에서 기존 V9 seed→V17 upgrade·전체 17 migration·24 업무 table과 서버 접근/두 AI reader의 이력 접근 거부 확인.
- AI Python 3.14: 관련 단위/계약 검사 134개·변경 Python ruff 통과. 두 저장소 diff 검사·서버 shell 문법 검사 통과.
- 초기 서버 회귀는 레거시 예약 연결 6건·H2 JSON 비교 1건이 실패해 수정했다. 새 fixture 검사 추가 중 AutoCloseable 미구현 객체를 try-with-resources로 쓴 컴파일 오류 1건도 수정했다.
  이후 H2/PG의 성공 결과로 앞선 실패 기록을 삭제하지 않는다.
- 실제 로컬 Spring→FastAPI→Spring DB/K4를 확인했으며 제공사 응답과 계산 입력은 합성이다. 고객 효과·실제 유료 LLM·개발 배포·장기 보존 관측은 확인하지 않았다.
