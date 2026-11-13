# 0003. BE-03 PostgreSQL 원본 저장 (#76 / IPGO-121)

상태: 개발 환경 V15·DB 저장 전환은 2026-11-07 승인 후 적용됐으며, 2026-11-13 격리 복원 검증까지 완료했다.
판정 근거는 [Server #76](https://github.com/IpgoITGO/ipgo-server/issues/76), [적용 이력 Infra #33](https://github.com/IpgoITGO/ipgo-infra/issues/33),
[복원 결과 Infra #67](https://github.com/IpgoITGO/ipgo-infra/issues/67)이다. 기존 원본 이전·삭제는 하지 않았다.
아래 11/3·11/5 로컬 검증과 11/7 개발 적용·11/13 복원 시험은 출처·실패·검증 범위를 구분한다.

## 승인한 범위와 저장 정책

- 신규 업로드만 명시적으로 선택한 `database` 모드에서 DB에 저장한다. 기본 `local`과 기존 `s3`는 유지한다.
- 전체 회원·창고·성공/실패·모든 revision의 DB 원본 합계 상한은 **256MiB (268435456 bytes)**다.
  `IPGO_UPLOAD_DATABASE_MAX_BYTES` 기본값은 이 값이며 더 작은 검증 한도는 허용하고, 0 이하·256MiB 초과 설정은 기동을 거부한다.
- 파일별 기존 XLSX·10MiB 상한을 유지한다. 총 보관량 초과는 HTTP507이며 자동 삭제로 공간을 만들지 않는다.
- 원본마다 `created_at`, `retain_until`을 기록해 **최소 30일** 보존한다. CHECK와 UPDATE/DELETE/TRUNCATE trigger로
  보존 기간의 일반 변경·삭제를 차단한다. 앱에는 원본 UPDATE/DELETE/TRUNCATE 권한이 없다.
- 30일 뒤에도 자동 삭제하지 않는다. 운영자의 별도 승인된 정리에서만 삭제할 수 있다. 미래 정리 도구·스케줄은 추가하지 않았다.
- 기존 S3/local 원본·경로·revision은 `EXTERNAL`로 보존한다. 이전·삭제·재업로드·대기 작업 재등록을 하지 않는다.

V15는 `storage_kind`, 별도 `upload_originals(bytea)`와 `ai_upload_originals_v1`을 추가하며 개발 DB에 적용돼 있다.
기존 V1~V14와 적용된 V15 SQL을 수정하거나 재적용하지 않는다. 새 환경의 적용은 별도 승인과 실제 이력 확인이 필요하다.
raw bytes 합계는 실제 DB/TOAST/WAL/백업 소비량과 다르므로 256MiB를 운영 자원 수용 성능으로 해석하지 않는다.

## 무결성·동시성

기존 파일명·XLSX·압축/XML·기간·SHA-256·중복/revision 검사를 유지하고 최신 XLSX 검증기의 파일명 인자를 전달한다.
검증용 임시 파일만 사용한 뒤 지우며 지속 로컬 원본은 만들지 않는다. 신고 크기가 작은 입력도 실제 바이트 수로 제한한다.
upload row → storage marker·원본 INSERT → 활성 job INSERT를 같은 트랜잭션으로 수행해 어느 단계든 실패하면 전체 rollback한다.
`pg_advisory_xact_lock(7601,1)` 이후 합계를 읽어 서로 다른 창고/프로세스도 같은 총량 검사를 직렬화한다.
목록·상태 조회에는 binary를 매핑하거나 노출하지 않는다. 원본 다운로드 API는 추가하지 않는다.

## 서비스 전용 원본 조회

- `ipgo_ai_service_reader`는 AI 서비스 전용 로그인이다. 기존 공유 `ipgo_ai_reader`와 사람용 계정에는 raw view를 부여하지 않는다.
- 전용 계정은 CONNECT·schema USAGE·승인된 5개 view SELECT·기본 read-only만 사용한다.
  업무/원본 table 직접 SELECT·쓰기·DDL·migration 권한은 없다. 새 역할에 다른 역할을 상속시키지 않는다.
- AI의 `IPGO_ORIGINAL_DATABASE_URL`에 이 계정의 별도 Secret 연결을 주입한다. `DATABASE_URL`로 자동 대체하지 않는다.
  metadata용 DATABASE_URL은 기존 4개 view reader를 유지하거나 서비스 전용 reader로 전환할 수 있다.
- 원본 조회 시 실제 `current_user`가 전용 role인지 확인한다. 다른 role이나 연결 미설정은 fail closed다.
- view는 **회원별 RLS가 아니다**. 신뢰하는 서비스만 자격 증명을 가지며 앱이 uploadId/userId/warehouseId/storageKey/filename/PROCESSING을
  같은 parameterized SQL에서 제한한다. 사람이 전용 자격 증명을 공유해 직접 조회하는 것은 허용하지 않는다.
- size/MIME/SHA-256·read-only transaction·연결/SQL timeout·rollback을 검사한다. 큰 크기 선언은 CASE로 binary를 읽지 않는다.
  DB source가 없거나 무결성이 다르면 INVALID_SOURCE로 끝나며 S3/local로 우회하지 않는다.
- 기존 외부 key는 계속 S3/local reader로 읽는다. 원본·새 credential을 LLM에 보내지 않는다.

기존 운영 DB는 전체 bootstrap을 다시 실행해 기존 계정 비밀번호를 바꾸지 않는다.
승인된 one-off `bootstrap-original-reader`는 **새 서비스 계정만** 만들고 기존 계정은 건드리지 않는다.
동명 계정이 이미 있으면 중단하며 비밀번호를 초기화하지 않는다. 새 빈 DB의 전체 bootstrap은 4개 역할을 준비한다.
개발 환경의 전용 계정·Secret 연결은 이미 적용됐다. 위 준비 절차는 새 환경에 별도 적용을 승인받을 때 사용하며,
기존 계정·Secret을 다시 만들거나 비밀번호를 임의 초기화하지 않는다.

## 적용·복구 경계

개발 V15·전용 역할/Secret·호환 AI·서버 DB 모드는 후속 승인으로 2026-11-07 적용됐다.
11/5 로컬 코드 승인만으로 적용한 것이 아니며, 다른 환경의 migration·역할/Secret 생성·배포·모드 변경은 별도 승인 대상이다.
새 환경의 적용 순서는 대상 환경/SHA·자원·백업/복구 확인 → 전용 계정 준비 → V15 → 권한/원본 보존 검증 →
호환 AI+Secret 주입 → 서버(처음에는 기존 저장 모드 유지) → DB 모드 활성화다. 기존 Actions/OIDC·환경 승인을 유지한다.
기존 원본의 보관 기간을 줄이거나 Secret·업무 파일을 검증 로그에 복사하지 않는다.

복구는 신규 저장을 S3/local로 돌리고 이미 있는 DB 원본을 읽는 호환 AI는 유지하는 방식이다.
schema·원본·기존 실패는 보존한다. table DROP/down migration·임의 원본 삭제·구 AI로 무조건 복귀하지 않는다.
복구점의 자료를 확인할 때는 승인받은 별도 DB로 복원·비교한다. 기존 DB 덮어쓰기는 별도 승인 없이 하지 않는다.
RDS 자원·백업·복원·비용·정리 절차는 Infra가 관리한다. 원본 최소30일 보관과 RDS 복구점7일 보관은 다른 정책이다.

## 개발 적용·검증 — 2026-11-07

사전 snapshot available 확인 후 승인된 V15·전용 reader/Secret과 DB 모드를 적용했다.
합성자료의 실제 개발 API/worker 경로에서 기존 S3 EXTERNAL 원본과 신규 DATABASE 원본의 공존,
revision1/2·원본 크기/SHA·최소30일 보존·정제 DONE 및 Spring 결과 저장을 확인했다.
기존 업로드와 원본은 이전·삭제·재처리하지 않았다. [적용 이력](https://github.com/IpgoITGO/ipgo-infra/issues/33)을 따른다.

최초 통합 runner는 자동 계산0건 기대와 실제3건의 차이로 exit1이었다. 별도 독립감사에서 자동 계산3건 DONE·
유료 호출0·원본/정제/보존을 확인했으며 최초 실패를 성공으로 덮어쓰지 않았다. 실고객·최대부하·장기 관측 결과는 아니다.

## 격리 PITR 복원·정리 — 2026-11-13

[Infra #67](https://github.com/IpgoITGO/ipgo-infra/issues/67)에서 별도 승인 후 private Single-AZ micro/gp3 20GiB 한 개를 복원했다.
원본115건/9,521,850bytes의 크기·SHA·관계/보존 메타데이터, schema/Flyway/role/권한을 기준과 대조했다.
실제 reader의 view 조회와 원본 테이블 SELECT 거부, 기존 앱 코드의 조회1,720행 및 결과 digest 일치를 확인했다.
TLS verify-full·READ ONLY·rollback을 사용했으며 금지 쓰기권한은 catalog로 확인하고 DML은 시도하지 않았다.
이번 복원에서 CLEAN worker·PG·LLM 호출을 실행한 것은 아니다.

| 항목 | 승인 기준 | 관측 결과 |
|---|---|---|
| RPO: 요청시각과 검증 PITR시점의 차이 | 15분 | 9분53초 |
| RTO: 복원 요청부터 무결성·권한·앱 읽기 완료 | 2시간 | 9분38초 |
| 임시자원 존속: 요청부터 부재 확인 | 4시간 | 12분43초 |
| 증분 비용(세전) | 상한 USD5 | 보수 모델 약USD0.14, 실청구 미확정 |

기존 DB·7일백업·22개 snapshot·운영 서비스 설정을 보존했다. 검증 후 임시 DB를 final snapshot 없이 삭제하고
임시 snapshot/자동백업/추가로그/검증 컨테이너/키가 없음을 확인했다. 사전 HDD 백업·비공개 결과를 보존했으며
태그/스토리지 파라미터로 생성 전에 거부된 요청2회도 별도 실패로 기록했다. 실제 생성은1개, 복원본 검증은 첫 실행 성공이다.

이 결과는 한 시점의 데이터 집합에 대한 격리 복원 시험이다. 모든 최근 transaction 손실량이나 운영 endpoint 전환을
포함한 전체 서비스 복구 SLA를 실증하지 않았다. 최대 입력/동시부하·실고객 전체 흐름·실제30일 경과·장기 성장률도 미검증이다.
자동삭제·일괄 원본 이전·DB 증설은 이 결과만으로 추가하지 않는다.

## 로컬 검증 — 2026-11-05

- 최신 dev 통합 후 서버 JDK21 test/bootJar: 295개 중292통과·실패/오류0·조건부 생략3.
- AI Python3.14 전체579통과·Ruff 통과. 실제 공급자/유료 LLM 시험은 하지 않았다.
- 폐기용 PostgreSQL16에서 V1~V15 upgrade·기존 EXTERNAL 보존·전용/공유 reader 권한·retention 검사를 통과했다.
- DB 작업 이미지의 비관리자 bootstrap·계정 추가 전용 경로·중복 계정 거부·migration/verify를 통과했다.
- 실제 로컬 Spring/AI worker/PostgreSQL/Moto의 합성3상품×31일 연결 검사22개 통과: DB 원본 해시/크기·정제 DONE·일별기록93+93,
  원본/job 실패 rollback·동시202/507·원본 비노출·다른회원/창고 거부·30일 이전 삭제/수정/TRUNCATE 차단·기존 S3 bytes/metadata/정제 보존.
- 기간 경과 동작은 과거 시각을 가진 합성 row로 검사했다. 실제30일 관측·AWS 원본·최대부하/백업복원·브라우저는 미검증이다.
- 최초 통합의 XLSX 검증기 인자 컴파일 오류, 개발 의존성 누락, 작업 이미지 파일 접근/기존 reader 검증문 실패와 후속 성공은 구분했다.
  앱 역할 범위를 넓혀 검사를 통과시키지 않았으며 SQL 리소스를 비관리자 이미지 사용자도 읽도록 제한적으로 읽기 권한을 보장했다.
- 당시 로컬 검증에서는 실제 개발 V15·계정/Secret·배포·모드 전환·S3 이전/삭제를 실행하지 않았다.
  통합 기준은 Server dev e92487f와 AI dev 297cef4였다. 테스트 DB·컨테이너·network는 정리했다.

## 과거 로컬 검증 — 2026-11-03


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
