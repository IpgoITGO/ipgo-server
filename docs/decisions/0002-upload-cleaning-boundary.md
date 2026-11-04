# #26 정제 결과 저장·U2 조회 1차 구현

- 상태: 서버 내부 저장 경계 구현, 서비스 간 전송 계약은 미확정
- 확인일: 2026-10-30
- 관련: [IPGO-80](https://ehwiya.atlassian.net/browse/IPGO-80), [IPGO-69](https://ehwiya.atlassian.net/browse/IPGO-69), [server #26](https://github.com/IpgoITGO/ipgo-server/issues/26)

## 확인한 근거

- Jira IPGO-80 댓글은 정제 요청 단위·월 경계 검산·원본/DB 접근 방법을 추후 협의 대상으로 남겼다.
- Jira IPGO-69에는 일정과 담당자만 있으며 추가 계약 결정은 없다.
- [AI #15](https://github.com/IpgoITGO/ipgo-ai/issues/15)는 닫혔지만 마지막 댓글은 제안이 미확정임을 명시한다.
- AI dev의 `schemas/engine.py`는 `files` 배열을 유지하고, `engine/clean.py:clean()`은 아직 미구현이다.
- AI 결정 문서는 S3·조회 전용 DB·SQS를 목표로 정했지만, 서버의 같은 계약 PR #36은 아직 열려 있다.

## 구현한 범위

`GET /api/uploads/{uploadId}`는 Bearer 인증과 창고 소유자를 확인한다. 없는 업로드는 404,
다른 회원의 업로드는 403이다. 내부 jobId·storageKey·원본 결과 행은 응답하지 않는다.

새 업로드에는 서버가 UUID jobId를 부여한다. 기존 행의 null jobId를 임의로 채우거나 발송하지 않는다.
`UploadCleaningService.complete(uploadId, jobId, result)`는 AI CleanResponse 필드 구조를 받아
해당 업로드와의 연결, 기간, 참조 상품·LOT, 중복 행, 수량 저장 범위, 검증 집계를 확인한다.
상품·LOT 등록, 두 일별 수불 테이블 저장, 검증 보고서와 DONE 전환은 같은 트랜잭션이다.
유효하지 않은 결과는 전체 rollback하며 PROCESSING 상태를 유지한다.

같은 창고의 결과 처리는 직렬화한다. 같은 jobId가 재전달되면 DONE/FAILED 행을 다시 쓰지 않는다.
기존 LOT가 다른 상품에 붙는 결과는 거절하며 셀러가 수정한 상품명·원가·사용 여부는 보존한다.
새 revision의 결과는 원본 upload_file_id에 저장하여 이전 revision의 자료를 변경하지 않는다.

`fail`은 정해진 실패 코드와 안내 문구만 저장한다. 엔진의 원본 예외·접속 정보는 저장하거나
사용자에게 반환하지 않는다. 최종 실패 후 늦게 온 성공은 상태를 되돌리지 않으며 재업로드는 새 작업이다.
전송 adapter는 일시 장애를 즉시 최종 실패로 처리하지 말고 재시도/DLQ 정책과 함께 연결해야 한다.

## U2의 값 규칙

| 상태 | asOfDate | validation | error |
|---|---|---|---|
| PROCESSING | null | null | null |
| DONE | 정제 결과의 마지막 날짜 | 검증 집계·제외 상품과 서버 productId | null |
| FAILED | null | null | 안전한 code·message |

`periodStart`는 업로드 파일명의 월 시작일이며 `periodEnd`는 성공 시 실제 자료의 끝으로 갱신한다.
검증 완료는 계산 완료와 다르다. `runId`는 #27 전까지 null이고 `matchedReceipts`는 매칭 정책 확정 전까지 빈 배열이다.
추정 입고 주기는 보고서에 보관하지만 누적 기준이 정해지기 전에는 S1 기본값이나 셀러 확정값을 변경하지 않는다.

Client dev는 PROCESSING/FAILED일 때 검증 결과를 읽지 않으므로 현재 분기와 호환된다. 다만 TypeScript의
`asOfDate`·`validation` 타입은 상태별 nullable로 조정하고, DONE 화면의 '계산했어요' 문구와 error 표시를
프론트 담당자와 맞춰야 한다. 이번 서버 작업에서는 프론트 코드를 수정하지 않았다.

## 전체 완료 전 필요한 결정·구현

1. 파일 1개 요청 schema, 이전 유효 업로드 선택과 월 경계 검산 계약을 AI·서버에서 함께 확정한다.
2. 큐 발송/수신, durable 재시도와 DLQ, 큰 정제 결과의 전송 방식을 확정하고 adapter를 연결한다.
3. 기존 발주와 수불 입고를 매칭하는 날짜·수량·분할 입고·중복 방지 기준을 확정한다.
4. 누적 자료로 입고 주기를 추정하는 기준과 S1 연결을 확정한다.
5. 실제 AI clean 실행 → 결과 저장 → U2와 실패 경로를 PostgreSQL에서 검증한다.

현재 서버는 정제 작업을 자동 발송하지 않는다. 따라서 일반 업로드는 전송 adapter가 연결될 때까지
PROCESSING에 머문다. 내부 저장 테스트 통과를 #26 전체 완료 또는 실제 AI 연동 성공으로 기록하지 않는다.

## 전달받은 정책 제안 (2026-10-30, 확정 전)

사용자가 팀원에게 받은 답변을 전달했다. 아래는 전달받은 제안이며 이 문서로 팀 합의를 확정하지 않는다.

- 월 경계: 같은 창고에서 바로 전 달의 유효한 업로드를 서버가 선택해 `previousUploadFileId`로 전달한다.
  없으면 null이다. 엔진이 해당 파일의 마지막 날 재고로 이번 파일의 첫날을 검산한다.
  후속 계약은 [AI #33 / IPGO-102](https://github.com/IpgoITGO/ipgo-ai/issues/33)에서 추적한다.
- 발주: 등록 이후 해당 상품의 입고가 확인되면 도착 예정일이 빠른 발주부터 매칭한다.
  발주량 이상이면 RECEIVED, 부족하면 유지 후 다음 업로드에서 다시 확인하며 RECEIVED는 재매칭하지 않는다.
  예정일이 기준일보다 과거이고 입고가 없으면 OVERDUE로 셀러 확인을 받는다.

구현 전에 아래 의미를 구체화해야 한다.

1. 이전 source의 '유효' 조건: DONE 여부, 새 revision 처리 중·실패 시 이전 DONE 사용 여부,
   전월 파일이 월말까지 포함하지 않는 경우의 검산 범위. 선택한 source는 작업 단위로 고정해야 한다.
2. 사용자는 부분 입고를 누적하지 않고 한 번의 입고량으로만 판정하는 방향을 선택했다.
   발주 100에 서로 다른 날 입고 40·60이 있어도 자동 완료하지 않는 안이며, 팀 합의 전이다.
   '한 번'을 같은 창고·상품·날짜의 입고 합계로 볼지, 부족하면 뒤 발주도 매칭을 중단할지 확인한다.
3. 같은 파일 재업로드·덮어쓰기의 행은 새 입고로 중복 계산하지 않는다. 단순히 uploadFileId별로 누적하지 않는다.
4. 발주 등록은 시각, 수불은 날짜만 갖는다. 등록 당일 입고 포함 여부, 예정일 동률·NO_DATE 발주의 순서,
   일부 입고된 채 예정일을 지난 발주의 OVERDUE 처리도 명시해야 한다.
5. 현재 V13의 AI 조회 view는 상품 일별 수불만 제공한다. 재고관리단위별 월말 조회가 필요하면
   최소 권한 view와 FastAPI reader를 추가해야 하며 원본 테이블 SELECT 권한을 임의로 넓히지 않는다.

위 내용을 [IPGO-80](https://ehwiya.atlassian.net/browse/IPGO-80)에 미확정 제안으로 등록했다.
유효 revision 교체 시점, 원본 수정 시 판정 취소·재검토, 분할 입고의 수동 확인도 함께 검토 요청했다.
팀 합의 전까지 월 경계 선택과 발주 상태·입고 배정 DB를 변경하지 않는다.

## 영향과 복구

새 migration·외부 callback endpoint·외부 queue 변경은 없다. 기존 V5의 job_id/validation_report와
V10의 두 일별 수불 테이블을 사용한다. 코드 rollback 시 저장된 일별 기록·JSON·원본 파일은 보존한다.
이전 코드로 돌아가면 U2 조회와 결과 저장이 제공되지 않으므로 프론트 기능 노출도 함께 조정한다.

## 이번 검증 결과

- Java 21 `./gradlew test`: 157개 통과, 실패·오류·건너뜀 0개.
- 새 `UploadCleaningIntegrationTests`: 11개 통과. 실제 HTTP 계층은 MockMvc로, 저장은 H2에서 확인했다.
- 인증·소유자 확인, AI camelCase JSON 읽기, 저장 결과 조회, 잘못된 job/source 거절,
  중복·누락 행 거절, 동시 중복 처리, 최종 실패, LOT 재연결 rollback, revision 이력·셀러 수정값 보존을 확인했다.
- `git diff --check`: 통과.
- Docker daemon이 실행 중이 아니어서 이번 변경의 실제 PostgreSQL 검증은 수행하지 않았다.
- 실제 FastAPI·SQS 요청/응답, 발주 매칭과 누적 입고 주기 추정은 아직 검증하지 않았다.
