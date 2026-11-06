# 입고 정정 검토와 수동 확정 (#134 / IPGO-159)

사용자가 승인한 [구현 방향](https://github.com/IpgoITGO/ipgo-server/issues/134)에 따른 서버 계약이다.
UI는 Client243/IPGO-160에서 별도로 연결한다. 이 문서의 구현·로컬 검증과 실제 개발 DB 적용/배포는 구분한다.

## 현재 상태와 과거 기록

U2 `receiptMatching`은 해당 업로드 당시의 불변 snapshot이다. 사용자 확정 뒤 과거 U2의 SOURCE_CHANGED를
지우지 않는다. 최신 처리 상태는 아래 API에서 OPEN/RESOLVED로 읽는다. reviewId는 V16 입고 사건 ID이며,
확인 필요이거나 사용자 결정 이력이 있는 본인 사건만 조회한다. 현재 사건이 없는 ID는404, 타인 사건은403이다.
조회는 저장·검토 완료 처리·재계산을 하지 않는다.

첫 버전은 SOURCE_CHANGED만 확정한다. 수량 부족·등록 당일·검산 불가 등의 기존 사유 자체를 임의로
자동 매칭하거나 새 수불 자료로 만드는 기능이 아니다. 등록일 판단은 Asia/Seoul을 사용한다.

## HTTP 계약

기존 Bearer 인증·쿠키 사용 시 CSRF 정책을 그대로 적용한다. POST decisions는  기존 빌링 쓰기 권한도 검사한다.
읽기와 preview는 쓰기 권한 만료와 구분한다. DTO/Bean Validation은 생성되는 OpenAPI에도 노출된다.

| 메서드/경로 | 요청/응답 |
|---|---|
| GET `/api/receipt-corrections` | `state=OPEN`(기본)/RESOLVED/ALL, `cursor=0`(기본), `size=20`(1~50). `{items:[Detail],nextCursor}`. cursor는 마지막 사건 ID, 다음 페이지가 없으면 null |
| GET `/api/receipt-corrections/{reviewId}` | 아래 Detail |
| POST `/{reviewId}/preview` | `{expectedVersion,action,receiptIds}` → `{version,action,before,after,changes,warnings,mayRecalculate}` |
| POST `/{reviewId}/decisions` | preview 요청+`{reason,idempotencyKey}` → `{decisionId,currentState:"RESOLVED",version,runId}` |

두 POST의 전체 prefix는 `/api/receipt-corrections`다. 성공은200이다. action은KEEP/REPLACE,
receiptIds는 중복 없는 양수 ID 최대200개(KEEP는 빈 목록), reason은 공백만 아닌 최대500자,
idempotencyKey는 UUID다. expectedVersion은64자 hex 문자열이며 내부 내용을 해석하지 않는다.

Detail에는 상품/창고/stockDate, `currentState`, `version`, `sourceReady`, 최초 배정 근거 `originalSource`,
최신 revision 근거 `currentSource`, 현재 allocations, 본인 상품의 candidates, capabilities,
warnings, 명시적 lastDecision과 최근20개 decisions가 있다. 현재 결정은 시간 정렬 대신 사건의 마지막 결정
포인터로 식별한다. Source에는 uploadId/revision/존재/수량/검산 유효성/의미 지문이 있다.
Candidate의 selectable을 정본으로 삼되 저장 시 서버가 다시 검증한다. 과거 이름은 배정 당시 값,
새 배정 이름은 현재 상품명으로 기록한다.

`capabilities={keep,replace,clear}`는현재 OPEN이고 최신 revision이 DONE일 때만 열리며,
replace는 존재하는 검산 통과 양수 입고에만 허용한다. 누락·0·검산 불가에서도clear/KEEP는 가능하다.
최신 revision이PROCESSING/FAILED이면 이전 DONE으로 되돌아가 확정하지 않는다.

## preview와 확정

- KEEP: 기존 배정을 유지하며 receiptIds는 빈 목록이다. 최신 원본이 부족/누락/검산 불가면 그 경고를 남긴다.
- REPLACE: 최종 배정할 발주 전체 ID를 명시한다. 빈 목록은 전부 해제다. 같은 창고/상품/소유자이고
  등록일 다음 날 이후 입고이며 날짜 있는 미입고 발주 또는 해당 사건의 기존 완료 발주만 고를 수 있다.
  타사건 완료·취소·NO_DATE·등록 당일은허용하지 않는다. 발주 전량 단위이며 총량은최신 입고량 이하다.
- 현재 배정 중 최종 목록에서 빠진 발주는미입고로 되돌리고 기존 #91의 서울 오늘/예정일에 따라
  PENDING/OVERDUE 공개 상태와 반영 여부를 계산한다. 자동 선택·다른 날 부분입고 합산은없다.
- preview의 changes는바뀌는 발주의 전후 상태와 예정 입고 후보 반영 여부다. 실제 예측 수량이나
  계산 완료를 보장하는 값이 아니다. mayRecalculate는설정/엔진의 사전 조건 안내이며 최종 runId가정본이다.
- 배정 변화가 있을 때만 기존 계산 경로를 호출한다. 확정 기본값/엔진 등이 준비되지 않으면
  runId=null일 수 있다. KEEP와동일 배정 목록은불필요한 계산을 만들지 않는다.

version은 사건 version·최신 파일/status·의미 지문·배정·관련 발주 상태/수량/날짜·서울 업무일 등의
검토 맥락을 묶는다. 오래된 version은409 RECEIPT_CORRECTION_STALE, 확정할 OPEN/최신 DONE이 아니면
409 RECEIPT_CORRECTION_UNAVAILABLE, 같은 요청 키에 다른 요청이면409 RECEIPT_CORRECTION_KEY_CONFLICT다.
입력 범위·수량 오류는400 INVALID_INPUT, 인증·권한 오류는기존401/403 계약을 따른다.

멱등 키는 사용자 범위이며 reviewId/expectedVersion/action/정렬한 ID/앞뒤 공백 제거한 이유를 비교한다.
같은 키·같은 요청은 최신 화면이 바뀌었어도 최초 응답을 반환한다. 이 응답의 version/runId는 최초
결정 당시 값이므로 UI는현재 상세를 다시 읽어야 한다. 응답 유실 시 키를 바꿔 다시 저장하지 않는다.

RESOLVED 사건의 새 결정은 원본이 실질적으로 바뀌어 OPEN이 다시 열린 뒤에만 가능하다. 같은 요청키 재시도는 이 제한과 구분한다.

## 원본·감사·동시성

V19은 사건에 확정 의미 지문/단조 sequence/최근 결정 ID를 더하고,
`receipt_correction_decisions`와 `receipt_allocation_history`를 추가한다. migration은 기존 V16 배정을
BASELINE으로 먼저 복사한다. 이후 자동 배정과수동 ASSIGN/RELEASE를 원장에 추가하고, 현재 배정 projection과
RECEIVED를 같은 transaction에서 관리한다. 결정에는행위자·서버 시각·이유·원본/revision·전후 배정·버전·요청 해시·응답을 보존한다.
PostgreSQL 서버 역할에는 원장의 UPDATE/DELETE 권한을 주지 않고 AI reader 접근도 차단한다.

창고 잠금 이후 사건/최신 파일/발주를 다시 읽어 정제·발주 수정/취소·동시 확정을 직렬화한다.
발주/배정·확정 상태·감사·계산/outbox는 한 transaction이다. 저장/감사/outbox가실패하면 모두 rollback한다.
다른 창고에서 동일 사용자 멱등 키가 경합해도 unique 제약으로 거절하고 해당 transaction 전체를 rollback한다.

확정한 의미 지문(상품/일자의 존재·수량·물리적 검산 유효성)이 같은 자료를 다시 올려도 확정/배정을 되풀이하지 않는다.
변경이 발생하면 새 검토를 열고, 원래 내용으로 돌아와도 현재 열린 검토는 자동 종료하지 않는다.
같은 원본의 남은 수량을 나중에 등록한 발주에 다시 배정하지 않는다. 과거 U2·원본·과거 run은 변경하지 않는다.

## 검증·적용·복구

Gradle은 테스트 DB 종류(H2/외부 DB)를 입력으로 구분한다. 외부 DB 검사는 UP-TO-DATE/build cache로
재사용하지 않는다. 접속 URL·계정·비밀번호는 캐시 입력에 기록하지 않는다. 환경을 바꾸어 실행할 때
실제 `:test` 실행과 JUnit 결과를 확인하며, 이전 PostgreSQL 결과의 재사용을 H2 성공으로 세지 않는다.

H2와 폐기 PostgreSQL에서 현재/과거 분리·권한·부족/누락/0/검산 불가·동일 자료/재정정·멱등성·경합·rollback을 검사한다.
`scripts/testing/verify-receipt-matching.sh`는 V18 기존 배정 fixture → V19 기준 이력/AI view 불변·권한 검사 후
서버 역할로 정정/기존 매칭·업로드·발주·엔진 회귀를 수행한다. 테스트 정리/fault 함수는 이 폐기 DB에만 설치하고 제품 migration에 넣지 않는다.

V19을 적용하기 전에는 이 코드를 배포하지 않는다. 실제 개발 DB의 이력1~14,16과 V15 보류 및 후속 V17 승인 상태를 확인하고
백업·정확한 선택 번들·체크섬·적용 후 조건·복구안을 별도 승인받는다. 전체 migrate로 V15/V17을 임의 동반 적용하지 않는다.
추가 DDL/기준 이력 생성은 배정량에 따른 잠금/시간 영향이 있다. 배정 변경 이후에는 코드만 구버전으로 되돌리지 않고
쓰기/정제 중지·보호 코드 유지와 이력을 보존한 보상 결정을 검토한다. 원장·snapshot을 삭제해 되돌리지 않는다.
실제 UI 연결·개발 migration/배포·통합 시험은 별도 단계이며 이 문서는 그 성공을 주장하지 않는다.

## PR 준비 시 최신 dev 반영

#33의 V18 호출 이력이 먼저 dev에 반영돼 정정 schema를 V19로 옮겼다. 기존 V18은 수정하지 않았다.
전체 PostgreSQL 검증 기준은19개 migration/33개 업무 table이며, Database CI에서 정정 transaction 회귀도 실행한다.
