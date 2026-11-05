# 0009. 현재 AI 설명·시나리오 계약 대조

상태: **구현·실측과 대조한 계약 반영안 / 공동 검토 대기** (2026-11-05)

관련: Server #25, AI #24·#44, Client #78. 기준 Server b66bc8a, AI 27a0063.
기존 Server #25와 AI #24의 Spring 책임 합의를 보존한다. 이 문서를 새 담당자의 승인으로 기록하지 않는다.

## 계약 결정과 코드의 대응

| 항목 | 현재 구현·기존 합의 | 저장·공개 범위 |
|---|---|---|
| 설명 입력 | 정상 결과의 insightInput을 검증하고 DB 저장 정밀도의 계산값으로 치환 입력을 준비 | 준비된 경우 ai_explanations.request_payload의 input/values/fallback. 비활성이면 빈 payload일 수 있음. 원래 계산 요청은 result.input_snapshot 및 run.selection_snapshot에 보존 |
| 수명 | 새 계산은 새 결과/설명 행을 만들며 이전 결과를 소급 수정하지 않음 | 자동 TTL·과거 DISABLED 일괄 재생성 없음. 보관 정책 일반화를 뜻하지 않음 |
| 값 대입·대조 | Spring이 표시값·자리 표시자·비교 방향을 검사하고 값을 대입. FastAPI는 초안의 형식/필수 이름/숫자 노출을 검사 | 입력에는 placeholder 이름 목록만 전달. 화면 값 dict를 LLM에 전송하지 않음 |
| 내부 응답 | FastAPI는 validation/draft/failReason/promptVersion/latencyMs/costUsd 반환 | mode/templateVersion/failDetail을 반환하지 않음. Spring reason_code 및 versions의 template=spring-1/prompt에 저장. 상세 원문을 공개 오류에 싣지 않음 |
| 공개 K4 | GET은 저장된 설명 조회이며 생성 호출 endpoint가 아님 | resultId/mode/isFallback/text/generatedAt. mode는 AI/FIXED/TABLE_ONLY, DB LLM/TEMPLATE/NONE과 대응. text는 단일 문자열 또는 null |
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
- AI24에는 정무영 최종 확인, AI44에는 최종 이름/문장 형태/추가 실패 사유 확인이 열린 기록으로 남아 있다.
  현재 공개 구현은 위 단일text/AI·FIXED·TABLE_ONLY다. 이 대조안을 각 담당자의 최종 수락으로 꾸미지 않는다.
- CUSTOM 변경 후 설명 재생성 기능은 현재 없다. 원래 설명을 보존하는 현행 동작과, 미래 재생성 범위의 제품 결정은 구분한다.
  모델/실제token 추가 전달·기록은 #33, 전체 정제/계산 오류·동시성은 #95, 과거 정확도 history는 #93이다.

Server25 종료 전에는 공동 확인과 API 명세서 반영을 확인해야 한다. 문서 PR 존재나 제한적 생성 성공만으로 종료하지 않는다.
