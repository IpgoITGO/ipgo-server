# 0005. 계산 이후 AI 설명 생성·저장

- 상태: 로컬 구현, 실제 배포·유료 호출은 별도 승인 대상
- 관련: Server25(IPGO-69/IPGO-71), Server61, Client78(IPGO-60), ipgo-ai 0002
- 기존 V11 설명 테이블을 사용하며 DB migration은 추가하지 않는다.

## 처리

정상 계산의 설명 입력을 계산 transaction에서 보존한다. `InsightGeneration`은 commit된
PENDING 행 하나를 조건부 UPDATE로 claim하고 DB transaction 밖에서 내부 HTTP를 호출한다.
전용 단일 스레드를 사용해 계산/SQS의 기본 스케줄러를 LLM 대기로 막지 않는다.
`InsightEngineClient`는 고정 `/engine/insight`, 리다이렉트 금지, 연결1초·전체15초(설정 최대30초),
응답64KiB 제한을 사용한다. 정상 응답의 promptVersion·validation·latency/cost를 검사한다.

`InsightRenderer`는 해당 상품 계산 사실과 입력의 위험 상태·방향·조건·시나리오를 대조한다.
원래 입력을 검증한 뒤 DB에 실제 저장된 정밀도의 값을 다시 읽어 치환·방향에 사용한다.
공유 DisplayQuantities로 재고 내림/수요 올림, 비율 HALF_UP 정수%를 적용한다. 미산출 위험은 만들지 않는다.
실제 상품명 대신 고정 별칭만 전달하며 LLM 요청에는 이름 목록과 방향만 포함한다.
실제 값은 Spring의 request_payload 내부 snapshot·inserted_values에만 남는다.
서버는 초안의 원시 숫자·미등록/미치환 자리 표시자를 거부한다. 내용/정책 검사는 기존 FastAPI 검사기 책임이다.

K4 GET은 생성 중202, 검증·치환 완료 AI, 실패 시 FIXED 기본 문장 또는 TABLE_ONLY를 반환한다.
계산/시나리오는 설명 결과와 독립적으로 완료된다. 사용자 소유권 검사는 기존 K4를 유지한다.

## 중복·실패

result당 unique 설명 행과 계산 결과 재전송의 기존 완료 검사를 유지한다. PROCESSING을 다시
PENDING으로 돌리지 않는다. 알 수 없는 호출 결과/작업기 중단은 timeout+30초 뒤 기본 문장으로
마감해 중복 과금을 막는다. 이는 exactly-once 외부 호출 보장이 아니다. claim 후 호출 전
중단되면 호출0회로 종료할 수 있고, 호출 성공 후 DB 저장 실패면 AI 결과가 유실될 수 있다.
오류는 원문 대신 코드/예외 종류만 로그에 남긴다. 응답/API key/prompt 로그를 추가하지 않는다.

## 적용과 복구

기본 비활성은 유지한다. 배포 전 사용자에게 고정 Server/Client SHA·환경·활성화 범위·유료 호출
상한/비용·시험 계정과 결과·rollback을 제시하고 승인받는다. 실제 FastAPI key·설정은 값 출력 없이
별도 확인해야 하며 이 로컬 구현/가짜 LLM 성공으로 승인/실제 모델 성공을 대체하지 않는다.

코드 복구는 작업 브랜치의 변경을 표준 revert하거나 이전 승인 배포 SHA를 사용한다.
활성화 후 롤백 시에는 신규 계산 접수를 멈추고 PENDING/PROCESSING을 확인해 완료/기본문장
마감 후 `IPGO_INSIGHT_ENABLED=false`로 전환한다. 설정을 먼저 끄면 남아 있는 작업은 처리되지
않을 수 있으므로 잔여 행은 별도 승인된 정리 범위로 기록한다. 기존 설명/계산 행은 삭제하지 않는다.
기존 DISABLED 행, CUSTOM 설명 갱신, 원본DB 전환/예약/인프라 변경은 이번 범위 밖이다.
