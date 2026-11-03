# 0003. BE-03 PostgreSQL 원본 저장 (#76 / IPGO-121)

상태: 로컬 구현·격리 검증 대상. 실제 개발 DB migration·배포·기존 파일 이전은 미실행이다.
서버 dev `011863a8`, AI dev `1d6b772e` 기준으로 같은 계약을 구현한다.

## 저장과 트랜잭션

새 `database` 저장 모드는 명시적으로 선택한다. 기존 기본 `local`과 `s3`는 유지한다.
`IPGO_UPLOAD_STORAGE`, `IPGO_UPLOAD_DATABASE_MAX_BYTES`가 활성화와 총 보관량 설정 이름이다.
DB 모드에서 총 보관량이 없거나 양수가 아니면 시작을 중단한다. 실제 환경 값은 별도 승인 대상으로 둔다.

V15는 기존 `upload_files`에 `storage_kind`를 추가하고 기존 기록을 `EXTERNAL`로 유지한다.
`upload_originals`는 `upload_file_id`를 PK/FK로 하는 별도 테이블이다. `content`는 `bytea`,
`content_type`은 XLSX 고정 MIME, `size_bytes`는 실제 바이트 수이며 생성 시각을 함께 보관한다.
빈 원본·10 MiB 초과·길이 불일치는 DB 제약으로 거절한다. FK는 원본을 가진 upload 삭제를 제한한다.
Spring에는 SELECT/INSERT만 부여하고 원본 UPDATE/DELETE는 허용하지 않는다.

기존 형식·압축/XML·파일명·기간·해시·중복/revision 검사를 그대로 거친다. XLSX 검증용 임시 파일은
검사 후 지우며 지속 보관할 로컬 원본은 만들지 않는다. 실제 스트림도 최대 크기보다 1바이트만 더 읽어
신고 크기가 작은 잘못된 입력을 거절한다. 검증과 저장 사이의 SHA-256 일치도 확인한다.

업로드 트랜잭션에서 upload row 생성 → storage marker 변경·원본 INSERT → 활성 엔진 job INSERT를 수행한다.
어느 단계가 실패해도 함께 rollback한다. 엔진 비활성 시 원본·업로드만 저장하며 기존 건을 자동 재등록하지 않는다.
총량은 모든 회원·창고의 보존 원본 크기 합계다. `pg_advisory_xact_lock(7601, 1)`로 DB 모드 저장을
직렬화한 뒤 합계와 새 크기를 비교한다. 초과 시 HTTP507 `UPLOAD_STORAGE_FULL`이며 부분 기록을 남기지 않는다.
모든 서버는 같은 총량 설정을 사용해야 한다. 합계 조회 비용과 직렬화는 실제 부하 적용 전에 측정한다.

원본을 JPA entity 관계로 매핑하지 않는다. 업로드 목록·상태 조회 SQL/응답에는 원본 바이너리가 없다.
외부 HTTP 다운로드 API는 추가하지 않는다.

## 서버 ↔ AI 계약

SQS contractVersion과 `SourceFile` schema는 유지한다. DB 원본만 `storageKey`를
`db/<userId>/<uuid>` 형식으로 생성하며 기존 숫자 회원 경로와 구별한다.

V15의 새 view `ai_upload_originals_v1` 전체 컬럼은 다음과 같다.

`upload_file_id, user_id, warehouse_id, storage_key, original_name, status, sha256, content_type, size_bytes, content`

`storage_kind=DATABASE`의 upload·warehouse·original을 조인한다. view 소유자는 migration role이며
AI role에는 이 view의 SELECT만 추가한다. 업무 테이블·원본 테이블 직접 조회·쓰기·DDL은 추가하지 않는다.
view는 회원별 RLS가 아니다. AI가 uploadId·회원·창고·storageKey·원래 이름·PROCESSING을 SQL에서 함께 제한한다.

AI는 기존 source/월 경계 검증 후 `db/` 경로를 이 view에서 읽고 실제 크기와 SHA-256을 검사한다.
지정 크기보다 큰 원본은 SQL CASE로 content를 반환하지 않는다. 연결·SQL timeout과 read-only transaction을
사용하며 종료 시 rollback한다. DB 원본 누락·소유권/해시/크기 불일치는 `INVALID_SOURCE`로 실패한다.
연결 미설정·DB 장애에는 기존 오류/재시도 경로를 사용하며 local/S3로 대체하지 않는다.
기존 외부 key는 기존 local/S3 reader를 유지한다. 원본은 LLM에 전달하지 않는다.

AI 코드: `app/jobs.py`, `app/repository.py`. 계산 engine·prompt·기존 손계산 fixtures는 변경하지 않는다.
AI의 `docs/decisions/0005-database-original.md`에 동일 계약을 기록한다.
양쪽 모두 현재 로컬 후속 변경이다.

## 보관 정책과 적용 전 결정

- 파일별 상한은 기존 제한을 유지하며 DB 제약은 최대10 MiB다. 더 작은 runtime 제한을 둘 수 있다.
- 총량은 DB 모드 시작 전 명시한다. 이번 검증용 한도는 운영 추천값이 아니다.
- 보관 기간·회원별 quota·실패/구 revision 정리는 정책 미확정이다. 현재는 모두 보존하고 자동 삭제하지 않는다.
- 기존 local/S3 파일의 이전·삭제는 하지 않는다. 별도 이전 작업은 원본 복사·해시 대조·marker 전환과 복구가 필요하다.
- bytea·TOAST·WAL·snapshot/PITR·백업 보존량·복구 시간·메모리와 동시 업로드의 영향을 실제 환경에서 검토한다.
  파일 크기 합계는 실제 DB/WAL/백업 디스크 소비량이 아니다.

## 실제 DB 적용·배포·복구 — 별도 승인 필요

1. 확정할 것: 서버/AI SHA, 환경, 총 보관량·보관 기간, DB 여유·경보·백업/PITR·복구 목표와 영향.
2. 기존 승인 경로의 DB snapshot이 복구 가능함을 확인하고 V15 포함 DB task image를 검증한다.
   새 `database-task.sh`는 V1~V15를 대상으로 한다. 기존 one-off Actions/OIDC·환경 승인을 유지한다.
   새 image를 만들었다는 이유로 실제 migration을 실행하지 않는다.
3. 승인한 환경의 기존 절차대로 V15를 적용하고 role·view·기존 EXTERNAL 기록을 검증한다.
4. DB 읽기를 지원하는 AI를 먼저 배포하고, 서버는 처음에 기존 저장 모드를 유지한다.
   코드 배포와 DB 저장 모드 활성화·총량 설정은 각각 실제 영향과 SHA를 확인한다.
5. 승인된 테스트 원본으로 hash·정제 저장·실패·큰 메시지·UI 흐름을 검증한 뒤 사용 범위를 넓힌다.
   SQS/worker 구성은 AI57처럼 별도로 필요하다.

복구: 신규 업로드의 저장 모드를 이전 local/S3로 되돌리고 **DB 원본을 읽는 AI는 유지**한다.
기존 DB key가 있거나 대기 작업이 있으면 구 AI로 돌아갈 수 없다. schema와 DB 원본은 보존하며
테이블 DROP·down migration·일괄 상태 변경·기존 원본 삭제를 rollback으로 사용하지 않는다.
필요한 코드 복구는 원본 reader 호환성을 유지한 SHA로 한다. snapshot restore는 별도 복구 판단이다.

실제 DB에는 적용하지 않았으므로 운영 복구 실행 결과를 주장하지 않는다. 격리 시험과 업무 실측은 구별한다.

## 로컬 검증 — 2026-11-03

서버 전체 테스트187개·bootJar, AI 관련 테스트28개·Ruff, PostgreSQL16 migration/권한 검사를 통과했다.
기존 migration의 합성 EXTERNAL 기록을 보존한 채 V15를 적용했고 새 binary view와 금지 권한을 확인했다.
별도 내부 network의 실제 Spring·AI worker·PG16·Moto에서 합성3상품×31일 DB 원본 정제가 DONE,
일별 단위·상품 기록 각각93건이 됐다. DB 원본 bytes·크기·SHA-256도 입력과 일치했다.

원본 테이블 SELECT를 잠시 회수한 상태에서도 업로드 목록/상태가 정상이고 binary를 노출하지 않았다.
원본 INSERT 실패와 job INSERT 실패를 각각 주입해 upload/original/job의 부분 저장이 없음을 확인했다.
잘못된 XLSX·용량 초과의 무저장, 잘못된 정제 구조의 FAILED/INVALID_SOURCE,
실제 reader의 다른 회원·창고 거절을 확인했다. 다른 창고의 동시 업로드 두 건은
총량이 한 건만 허용할 때 HTTP202/507로 나뉘고 DB 합계가 한도를 넘지 않았다.

모든 DDL·권한 변경·자료는 폐기한 격리 DB에서만 실행했다. 실제 AWS·업무 원본·UI·최대 크기와 부하,
장기 보관·WAL/백업 규모·실제 복구·후속 계산/LLM은 검증하지 않았다. 작은 합성 입력의 연결 검증이며
과거 AI57의31상품 에뮬레이터 메시지 전송 실패를 변경하거나 성공에 합산하지 않는다.
