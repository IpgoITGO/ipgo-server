# 0006. 결과 698의 명시적 단건 설명 생성

> 최초698 설계 이력입니다. 현재811 실행 경로와 두 대상의 공유 예산은 [0007](0007-single-result-811.md)을 따릅니다.

- 관련: Server107, Server25, 결정0005. 구현·격리 검사 대상이며 배포/유료 호출은 별도 승인한다.
- 전체31SKU 자동 설명과 기존 worker는 false 유지한다. 별도 single-result-enabled는 기본false다.
- 단건 모드는 자동 설명 또는 worker가 켜져 있으면 시작/배포를 거부한다. AI의 기존 max_retries=0을 유지하고 서버 HTTP 재시도도 추가하지 않는다.

## 경로와 대상

결과 소유자의 GET `/api/results/698/insight/regeneration`은 읽기 사전 확인만 수행한다.
표식 예약·HTTP·원문/수치 응답은 없다. POST `/api/results/698/insight/regenerate`만 실제 시도를
시작하고 본문은 받지 않는다. 다른 ID·다른 소유자는403, 미존재는404, 미활성은503이다.
기존 Bearer 인증과 쿠키/CSRF 경계는 변경하지 않는다. 요청·설정에 ID나 한도 조절/초기화 옵션을 두지 않는다.

현재 DISABLED/NONE/LLM_DISABLED·원문/렌더문장 없음·비용0 또는null·토큰 이력 없음·표식 없음인
기존 행만 대상이다. 옛 Writer가 validation_passed=true를 쓴 경우도 이 조건을 만족하면 허용한다.
그 밖의 기존 성공·실패·사용 흔적은 INSIGHT_ATTEMPT_SPENT로 보수적으로 거부한다.

## 입력 복원

기존 DISABLED 행의 request_payload는 비어 있으므로 그대로 PENDING으로 바꾸지 않는다.
계산 결과는 OK, run은 DONE이어야 하고, 입력 snapshot의 owner/warehouse/단일product·날짜·배수와
저장값을 대조한다. 원가·실제 상품명·다른 SKU는 엔진에 보내지 않는다.

- methodFallback은 원래 policy.defaultMethod와 저장된 method를 비교한다. 전환이면 저장 사유도 검증한다.
- startStockEstimated는 저장된 start_date와 origin_date의 간격으로 복원한다.
- noDateReceiptQty는 원래 noDateReceipts 중 해당 product만 합산한다. 필드가 없으면0으로 추정하지 않는다.
- 위험은 배포된 ipgo-ai engine/calculate.py의 _apply_risk 규칙에 따른다. validObservationDays7~13은 LOW_GRADE이며 실제 저장 구간이 필요하다. 그 이상은 명시된 sampleSize와 실제 위험/기준 유무를 대조한다. 위험 자료가 모두 명시적으로 없는 경우만 INSUFFICIENT_SAMPLE이다. sampleSize 누락·위험 일부만 존재·구간 부재는 거부한다.
- P80/P100/P120의 DB 정밀도·중복·권장량을 검증하고, 설명 순서는 P80/P100/P120으로 정규화한다. CUSTOM은 사용하지 않는다.

InsightRenderer가 기존 숫자 치환/방향 계산/기본문장을 재사용한다. 값은 내부 payload에만 저장하고,
FastAPI에는 고정 별칭·허용 키 목록·비교 방향·플래그만 보낸다. 모델 출력 숫자·미등록 자리표시자·검사 탈락은 기본 문장으로 처리한다.
입력이 부족하거나 불일치하면409 INSIGHT_INPUT_UNAVAILABLE이며 표식을 소비하지 않는다.
실제698 행의 복원 성공·현재 프롬프트 품질·화면 효과는 배포 후 승인된 검증에서 구분해 확인한다.

## 영속적인 최대1회

기존 unique result 행을 FOR UPDATE로 잠근 뒤 복원을 검사한다. output_path의 미사용 상태를
CAS로 확인해 single-result/698/attempt-1과 PROCESSING을 함께 기록한다. REQUIRES_NEW commit이
끝난 뒤에만 HTTP를 시도한다. 기존 생성기의 응답 검증·저장 루틴을 재사용하며 output_path를 지우지 않는다.
메모리 카운터나 배포 횟수로 한도를 관리하지 않고 migration도 추가하지 않는다.

중복/동시 요청·다른 인스턴스·호출자 transaction rollback·SDK 오류·타임아웃·DB 저장 실패·재시작은
한도를 환불하지 않는다. 결과가 불명확한 시도를 재전송하지 않으므로 실제 provider 호출0회나
응답 유실이 가능하다. 이는 정확히 한 번의 모델 생성/전달/청구 보장이 아니다.

단건 전용 daemon은 표식698의 PROCESSING이 timeout+30초를 넘으면 기본 문장으로 마감한다.
복구는 HTTP를 호출하지 않으며 전체 PENDING 목록을 처리하지 않는다. GET은 상태만 읽는다.
DB 오류로 마감하지 못하면 예외 종류만 기록하고 다음 복구 주기에 마감을 시도한다. 표식은 남는다.

## 승인·복구

준비: 코드/합성 DB·로컬 HTTP·실제 FastAPI 검사기+FakeClient+폐기용 DB를 검사한다.
운영 준비 승인 후: 고정 Server source를 배포하되 자동 생성/worker false 유지 → GET 사전 확인 →
성공한 경우에만 별도 승인 범위의 POST1회 → 기존 GET mode/text와 같은 결과의 화면 재조회 확인.
실제 DB 기록/키값을 로컬 로그·공개 PR에 붙이지 않는다. POST 재시도·한도 초기화·전체 계산 재실행은 하지 않는다.

이번 기능 전 복구 기준은 Server dev6d726c1/TD14이며 AI297cef4/TD8과 별도worker0은 유지한다.
rollback은 single/자동 flags를 false로 하고, 진행 중인 시도의 기본 문장 마감·표식 보존을 확인한 뒤
이전 task 또는 표준 코드 revert를 사용한다. old task에는 단건 복구 코드가 없어 PROCESSING을
그대로 남긴 채 되돌리면 조회가202로 남을 수 있다. 표식을 삭제하는 SQL은 제공하지 않는다.
