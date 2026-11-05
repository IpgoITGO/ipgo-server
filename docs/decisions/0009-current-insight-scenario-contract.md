# 0009. 현재 AI 설명·시나리오 계약 대조

상태: **서버 계약 확정·명세 반영·실환경 대조 완료** (2026-11-05)

관련: Server #25, AI #24·#44, Client #78. 첫 대조 기준 Server b66bc8a, AI 27a0063. 완료 검증은 Server320014f/TD20·AI27a0063·Clientffcf8e8이다.
기존 Server #25와 AI #24의 Spring 책임 합의를 보존한다. 사용자 지시에 따라 정무영의 서버 계약 검토 역할을
EHWIYA가 인수했으며, 이번 검토·기록은 EHWIYA 계정으로 Codex가 수행했다. 과거 담당자의 승인이나
AI/Client 담당자의 새 승인으로 기록하지 않는다. API 명세서4장 반영은 서버 담당, AI 문서와 화면 반영은 기존 담당 범위다.

## 계약 결정과 코드의 대응

| 항목 | 현재 구현·기존 합의 | 저장·공개 범위 |
|---|---|---|
| 설명 입력 | 정상 결과의 insightInput을 검증하고 DB 저장 정밀도의 계산값으로 치환 입력을 준비 | 준비된 경우 ai_explanations.request_payload의 input/values/fallback. 비활성이면 빈 payload일 수 있음. 원래 계산 요청은 result.input_snapshot 및 run.selection_snapshot에 보존 |
| 수명 | 새 계산은 새 결과/설명 행을 만들며 이전 결과를 소급 수정하지 않음 | 자동 TTL·과거 DISABLED 일괄 재생성 없음. 보관 정책 일반화를 뜻하지 않음 |
| 값 대입·대조 | Spring이 표시값·자리 표시자·비교 방향을 검사하고 값을 대입. FastAPI는 초안의 형식/필수 이름/숫자 노출을 검사 | 입력에는 placeholder 이름 목록만 전달. 화면 값 dict를 LLM에 전송하지 않음 |
| 내부 응답 | FastAPI는 validation/draft/failReason/promptVersion/latencyMs/costUsd 반환 | mode/templateVersion/failDetail을 반환하지 않음. Spring reason_code 및 versions의 template=spring-1/prompt에 저장. 상세 원문을 공개 오류에 싣지 않음 |
| 공개 K4 | GET은 저장된 설명 조회이며 생성 호출 endpoint가 아님 | resultId/mode/isFallback/text/generatedAt 및 이번 PR에서 추가하는 failReason. mode는 AI/FIXED/TABLE_ONLY, DB LLM/TEMPLATE/NONE과 대응. text는 단일 문자열 또는 null |
| NO_DATE | noDateReceipts로 전달하며 입고수량 합산에서 제외 | 설명의 사실/boolean에만 사용. 예정일을 임의로 오늘로 바꾸지 않음 |
| 호출 대상 | 정상 OK이고 생성 입력이 준비된 결과만 승인 gate 범위에서 호출 | 계산불가/불필요/EXCLUDED/REUPLOAD_REQUIRED는 Spring의 고정 문장 또는 표시 생략. 미산출 위험을0/표본부족으로 조작하지 않음 |
| 기본 시나리오 | GET /api/results/{resultId}/scenarios | 기본 P80/P100/P120와 저장 CUSTOM. 권장0이면 기본 비율 목록이 비어 있을 수 있음 |
| 직접 입력 | POST 같은 경로에 {qty}, HTTP201·단일CUSTOM | resultId에서 원래 run/회원/상품/소스 요청을 역참조. Client가 새 계산 입력을 지정하지 않음. 입고단위 올림 후 전달 |
| CUSTOM 저장 | 결과당1행, 같은 올림 수량은 같은 ID 재사용, 서로 다른 동시 요청은409 | 기본 추천/비교표/입력/기존 설명 보존. isDuplicate는 기본 비율과 수량 중복 여부이며 재요청 여부가 아님 |

공개 API의 401/403/404, CUSTOM의400·409 SCENARIO_STALE/CHANGED·502·503·504와 null 처리는
[0004](0004-custom-quantity-scenario.md), 설명 준비/claim/실패 대체는 [0005](0005-insight-generation.md)를 따른다.
K4 PENDING/PROCESSING은202, 완료는200이며 정상 결과의 값이 부족하면 비교표만 제공할 수 있다.

## 전송·생성 시점과 시간 정책

CLEAN/CALCULATE는 현행 Spring outbox/SQS 경로다. contractVersion=1/jobId/jobType/payload envelope를
사용하며 결과 저장 transaction commit 뒤 ACK한다. CLEAN 재전송20분/만료2시간, CALCULATE 재전송5분/만료15분이다.
동일 jobId 재전송과 중복 결과 멱등성을 유지하며 과거 HTTP 초안의120/60초를 이 작업 만료와 혼동하지 않는다.

CUSTOM은 원래 입력을 이용한 내부 HTTP /engine/scenarios(기본10초), 설명은 계산 commit 이후 준비된 작업의
/engine/insight(기본15초)다. 내부 오류/시간초과/검사 실패는 기존 계산을 보존하고 설명의 기본 문장/표시 생략으로 처리한다.
자동 생성·worker·단건·batch gate는 별도 운영 승인 경계이며 GET이나 이 문서 반영으로 활성화되지 않는다.

## 합의 기록·실측·남은 범위

- 책임 합의: Server25 기존 답변과 AI24의 Spring 값 대입/기본 문장 책임 합의. API 명세서4장은 Server 담당,
  AI 결정문서/프롬프트는 AI 담당, 공개 응답 소비는 Client 담당으로 유지한다.
- Server PR98/100/112/121와 AI0002·Client78은 서로 다른 구현/검증 근거다. 실제 직접수량53 저장/재사용/오류화면과
  실제 AI5건 저장/독립GET/화면 대조는 각각 기존 실행의 근거이며 이번 문서 검증으로 새 표본을 만들지 않는다.
- Client API 명세서4장과5장의 K4 생성 시점 설명을 현재 경로에 맞추는 별도 문서 PR을 연결한다.
- AI24의 정무영 서버 확인 역할은 이번 인수 검토로 이어받는다. 현재 공개 구현은 위 단일text/AI·FIXED·TABLE_ONLY다.
  AI44의 시험표·기대 문구와 맞지 않는 항목은 아래 표로 남기고, 각 담당자의 최종 수락으로 꾸미지 않는다.
- CUSTOM 변경 후 설명 재생성 기능은 현재 없다. 원래 설명을 보존하는 현행 동작과, 미래 재생성 범위의 제품 결정은 구분한다.
  모델/실제token 추가 전달·기록은 #33, 전체 정제/계산 오류·동시성은 #95, 과거 정확도 history는 #93이다.

Server25는 아래 인수 결정·명세 dev 반영·실제 API/화면 대조를 마쳐 종료했다. 문서 PR 존재나 제한적 생성 성공만으로 종료한 것은 아니다.

## 2026-11-05 서버 인수 검토와 관련 후속

첫 검토 기준은 기존 Spring 책임 합의, Server b66bc8a 구현, Client의 응답 변환과 AI44의 U1~U6이다. 아래 AI44 시험표 후속은 확정된 Server25 계약 범위와 구분한다.
현행 계약을 유지하는 안을 검토했으며 사용자 후속 결정으로 이번 PR에서 공개 failReason을 추가한다. 기존 배포 b66bc8a에는 이 필드가 없으며 병합·배포·실환경 확인은 별도다.
새 계약을 구현한 것처럼 기록하지 않고 기존 구현·실측과 문서 반영 여부를 나눠 판단한다.

| 항목 | 서버 인수 검토 결과 | 남은 공동 작업 |
|---|---|---|
| U1 상태 이름 | 공개 AI/FIXED/TABLE_ONLY, 내부 저장 LLM/TEMPLATE/NONE 유지. 기존 Server25 답변과 동일하고 Client 변환기가 두 이름을 수용 | AI44의 최종 표기와 Client의 과거 호환 주석 정리 |
| U2 실패 사유 | 내부 reason_code에 LLM_TIMEOUT/LLM_ERROR/LLM_NOT_CONFIGURED/INVALID_INPUT/VALIDATION_FAILED/VALUE_MISMATCH 등을 저장. 사용자 결정으로 안전한 failReason을 공개하고 내부 reason_code 원문은 보존 | 구현·명세 dev 반영 및 실환경 필드/화면 확인 완료. 비null 사유는 로컬 API 경계 검사, AI44의 실제 LLM 실패 시험은 별도. reasonCode/raw 오류는 비공개 |
| U3 결과 상태 | EXCLUDED/REUPLOAD_REQUIRED/계산불가/입고불필요에서 FastAPI 설명 호출 없음. Spring 기본 문장 또는 text=null | AI44의 상태별 시험표와 현재 상품 상세의 영역 표시 기준 대조 |
| U4 문장 형태 | 공개 text 단일 문자열/null, 내부 draft 단일 문자열. Client는 text를 줄 단위 sentences로 변환하므로 공개 배열 추가는 불필요한 안으로 검토 | 배열 선호 의견과 다른 현행 단일text 계약을 AI44·명세서에서 일치시켜야 함 |
| U5 시험 날짜 | 과거 미정 일정을 임의 확정하지 않음. 기존 실제 Server→AI 생성/저장/화면 근거와 모의 실패 검사를 구분 | AI44 전체 실패 시나리오의 새 시험 일정·권한 범위는 별도 조율. #96 예약에 추가하지 않음 |
| U6 endpoint | 공개 /api/results/{resultId}/insight, Client 공통 /api prefix 뒤의 /results 경로. GET은 저장 조회 | 문서 경로 대조에 새 API 구현 불필요 |

원래 Server25의 여덟 결정 항목은 위 본문에서 저장/수명·치환 책임·내부 응답/저장·NO_DATE·호출 범위·
시나리오 endpoint/연결·공개 오류/빈 상태·명세 수정 담당으로 대응한다. 인수로 해소된 것은 서버 검토자 부재이며,
실제 배포의 새 failReason 검증과 공동 문서 반영 완료를 대신하지 않는다.

종료 판정: 사용자 후속 승인으로 Server PR124와 Client PR229를 dev에 반영하고 개발 배포·실제 소스를 확인했다.
사용자는 #96 예약을 취소하고 당일 예외 가동으로 먼저 검증하는 순서를 승인했다. #96 실제 만료/갱신/실패 UI를
고정 b66bc8a에서 확인한 뒤 새 Server320014f를 배포했다. API 명세4장 dev 원본과 계약 참조·실환경 대조를 마쳐 Server25를 종료했다.

### 공개 실패 코드 추가 결정

2026-11-05 사용자가 “K4에 안전한 실패 코드 failReason 추가”를 선택했다. 내부 DB에만 저장하는 대안보다
AI44와 기존 Client의 실패 안내를 연결할 수 있다는 이유로 선택했다. Server DTO에 nullable failReason을 추가하며
Client는 기존 failText/문장 변환을 사용한다. schema·migration·LLM 호출 정책은 바꾸지 않는다.

공개 허용 목록은 LLM_ERROR, LLM_TIMEOUT, VALIDATION_FAILED, VALUE_MISMATCH, LLM_NOT_CONFIGURED,
INVALID_INPUT, INPUT_UNAVAILABLE다. 실패 대체인데 알 수 없는 내부 코드는 LLM_ERROR로 축약한다.
정상 AI·LLM_DISABLED·상태별 고정 문장은 null이며 생성 중202 응답 형태도 유지한다.
원래 DB 사유, 원문 예외, URL, upstream 응답을 새 필드로 전달하지 않는다. 새 필드의 실환경 효과는 배포 후 확인 대상이다.

## 완료 검증의 출처와 한계

- Server PR124·Client PR229 dev 반영, 실제 Server320014f/TD20·Clientffcf8e8 개발 alias·HEALTHY/READY를 확인했다. 이후 문서 전용 후속 commit을 새 런타임 실측으로 세지 않는다.
- 새 배포의 K4 최신5건·과거AI설명5건·비활성698에서 failReason=null과 기존 mode/text를 확인했다. SKU32/result1958의 현재 TABLE_ONLY 화면과 같은 결과를 대조했다. 비null 허용 사유·미지 사유 축약·DB 원문 보존은 로컬 API 경계 검사 근거다.
- 실제 Server→AI 생성/저장·CUSTOM 성공/재사용/서버 오류 화면은 기존 #61/#94 근거를 재사용했다. 새 실제 유료 호출이나 모든 LLM 실패의 실측으로 확대하지 않는다. 첫 최신결과=AI 가정의 실행기 실패는 보존하고 현재/과거 결과를 분리해 검증했다.
- 사용자 추가 결정으로 현행 SQS C2는 QUEUED→DONE/FAILED, RUNNING은 호환 수용 값으로 확정했다. 실제 run88 자연 만료·실패 UI·복구 후 이전 결과 보존은 #95/PR126에 기록하며 기존 RUNNING 합성 검사를 실제 관측으로 바꾸지 않는다.
- AI44 전체 연동 시험·Client78 전체 분기·모델/실제token 이력·정확도history는 각 후속 이슈에 남는다. Server25의 계약 확정·반영 완료를 다른 이슈 전체 완료로 해석하지 않는다.
