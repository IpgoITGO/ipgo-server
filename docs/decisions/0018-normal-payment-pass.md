# TEST 일반결제 1개월 이용권

포트폴리오 개발 환경에서 토스 주문서형·결제창형 테스트 키로 한 번 결제하고 한 달 이용합니다.
자동 청구는 없습니다. 자동결제 상점 계약 없음(실제2003 응답) 때문에 일반결제 경로를 추가합니다.
기존 V17/V25 원장과 카드등록/자동청구 구현은 보존하며 일반결제 모드에서는 등록·재개·청구를 차단합니다.

## 설정과 적용

기존 `IPGO_BILLING_ENABLED=true`와 저장용 암호화/lookup·기존 TEST 키·상점 식별 설정은 유지합니다.
추가 `IPGO_PAYMENT_ENABLED=true`, `IPGO_PAYMENT_SECRET_KEY`에 짝이 되는 `test_gsk_`를 사용합니다.
`IPGO_PAYMENT_MERCHANT_ID`는 선택 사항이며, 알 때만 동일 상점의 값을 지정합니다.
비어 있으면 해당 시크릿으로 인증된 토스 응답을 사용하고 응답 MID를 주문에 저장해 이후 조회·취소 시 대조합니다.
프론트에는 같은 쌍의 `test_gck_`만 제공합니다. 서버 키를 VITE 변수에 넣지 않습니다.

승인된 개발 배포에서 `normal_payment_enabled=true` + `billing_test_enabled=true`와 기존 Secret ARN/복귀 origin을 지정합니다.
renderer는 `checkout_secret_key` 필드를 `IPGO_PAYMENT_SECRET_KEY`로 참조하며, 별도 새 IAM 권한은 필요하지 않습니다.
Secret 업데이트·DB V26 적용·Server/Client 배포는 실제 SHA·환경·검증·복구를 정리한 후 별도 승인합니다.
기존 Secret JSON의 모든 저장 키를 보존하고 checkout 필드만 추가합니다. 이 문서의 존재는 적용 완료가 아닙니다.

## API와 상태

- `GET /api/billing/plans`: 일반결제 모드의 interval=`ONE_TIME`, consentVersion=`one-time-month-2026-11-v1`.
- `POST /api/billing/payments`: Idempotency-Key 및 planId/priceVersion/consentVersion을 받습니다. 서버가 가격·orderId/customerKey·복귀 주소를 결정합니다.
- `POST /api/billing/payments/{orderId}/confirm`: paymentKey/amount를 받고 본인 주문·서버 금액과 대조한 뒤 `/v1/payments/confirm`을 호출합니다. 브라우저 복귀만으로 성공 처리하지 않습니다.
- `GET /api/billing/payments/{orderId}`: 본인 주문 조회. 불확정 승인/입금 대기는 저장한 paymentKey로만 조회하며 새 승인으로 우회하지 않습니다.
- `GET /api/billing/payments`: 본인 진행 중인 결제/환불. 계정의 상태 확인과 작업기가 같은 상태를 사용합니다.
- 기존 `POST /api/billing/invoices/{id}/cancel-payment`: 일반결제 주문은 새 gateway로 본인 최신 결제만 전액 취소합니다.

OPEN 주문은30분 유효하며 같은 의도는 재사용합니다. 타인·변조 금액·다른 paymentKey·탈퇴 계정은 거부합니다.
CONFIRMING lease와 사용자 row lock으로 중복 승인을 막습니다. 타임아웃/응답 유실은 UNKNOWN,
PG 응답의 주문/금액/통화/키/MID 불일치는 REVIEW_REQUIRED이며 이용권을 부여하지 않습니다.
가상계좌 WAITING_FOR_DEPOSIT는 PENDING입니다. 실제 DONE 및 approvedAt 확인 뒤에만 PAID와 이용권을 기록합니다.
승인 시각부터 서울 기준 한 달(월말 보정)의 기간을 부여합니다. 자동청구일은 NULL, 만료 즉시 유료 쓰기 권한이 끝납니다.
유효한 이용권 기간 중 중복 구매/요금제 변경은 막습니다. 만료 후 다시 결제할 수 있습니다.
환불은 PG의 전액 CANCELED/잔액0을 확인한 뒤에만 이용권을 종료합니다. UNKNOWN은 기존 권한을 보존하고 같은 멱등키로 복구합니다.
작업기는 조회·기존 취소 복구만 하며 일반결제 승인이나 정기결제를 새로 시작하지 않습니다.
14일 넘는 불확정 상태는 REVIEW_REQUIRED로 보존합니다. 자동 삭제나 임의 재결제는 하지 않습니다.

## 검증과 복구

로컬 H2/Fake PG의 HTTP·원장 검증과 실제 토스 TEST SDK 초기화는 구분해 기록합니다.
사용자가 카드·은행 인증을 마친 실제 결제 승인/입금/환불 성공은 별도 검증입니다.
새 프론트와 서버는 함께 적용합니다. 기존 프론트는 ONE_TIME을 해석하지 않으므로 단독 배포하지 않습니다.
롤백은 신규 결제 시작을 중단하고 CONFIRMING/PENDING/UNKNOWN/환불을 먼저 대조한 뒤 직전 승인 SHA·Secret 버전을 복구합니다.
일반결제를 끄는 renderer 경로는 checkout Secret 참조를 제거합니다. 자동청구를 재활성화할 의도가 없다면
기존 billing worker도 끄고 레거시 READY 작업이 없는지 확인합니다. V26과 결제 원장은 삭제하지 않습니다.
