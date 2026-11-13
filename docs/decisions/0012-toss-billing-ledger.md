# 토스 테스트 카드 월 정기결제 원장과 API

2026-11-06. Server #110의 서버 원장·API 구현이다. 기존 포트원 제안 대신 사용자가 승인한 토스 테스트 월 정기결제 정책을 적용한다. 클라이언트 변경·테스트 결제·운영 배포·실제 키 확인은 포함하지 않는다.

## 적용 정책

- 테스트 카탈로그 `test-2026-11-500-v2`: seller 500원/월, business 129,000원/월, KRW. 이전 v1의 seller 49,000원은 이력이다. 부가세·라이브 판매가 확정은 라이브 전 별도 단계다.
- #198 이후 신규 계정의 체험은 명시적 `trial/start` 선택 시각부터 정확히14일이며 카드 없이 시작한다. 기존 계정은 원래 signup 기준 체험 기간을 유지한다. 선택 시각·미선택 권한·V34와 Client 적용 조건은 [체험 선택 계약](0021-explicit-trial-choice.md)을 따른다. 체험 종료 후 자동 유료 전환하지 않는다.
- 명시적 유료 시작은 카드 등록 후 첫 결제를 요청한다. 서버 승인 확인 전에는 기존 체험/유료 상태를 유지한다.
- 최초 유료 시작의 서울 일자·시각을 월 anchor로 유지한다. 1/31→2월말→3/31, 윤년을 지원한다.
- 갱신 거절 후3일 유예, 이후 새 비용 발생 요청을 차단한다. 이미 결제한 기간과 결과 읽기는 보존한다.
- 해지는 다음 청구 중단 예약이다. 자동 환불하지 않는다. 기간 종료 전 유효 카드·동의를 확인해 해지 예약을 해제할 수 있다.
- 자동 반복 재결제는 없다. 확정 거절 이후 사용자가 같은 청구서의 재결제를 요청할 수 있다. 결과 미확정이면 새 청구를 허용하지 않는다.
- 카드 교체는 신규 청구를 만들지 않는다. 옛 카드의 진행 중 시도는 원래 수단을 유지하며, 옛 키 삭제 이벤트는 새 카드에 적용하지 않는다.

## API 계약

카드 등록창 실패 뒤 재진입할 때 같은 목적·요금제·가격 버전·동의의 미만료 OPEN 세션은 새로운 Idempotency-Key여도 재사용한다. 만료 시각은 연장하지 않는다. 다른 목적/요금제 또는 발급 중인 ISSUING 세션은409 BILLING_IN_PROGRESS다. 이전 등록 키를 만료/취소 뒤 다시 쓰면 각각 BILLING_SESSION_EXPIRED/BILLING_SESSION_CANCELED이므로 새 시도에는 새 키를 사용한다.

`POST /api/billing/registration-sessions/{id}/cancel`은 본인 JWT와 Idempotency-Key를 받고 요청 본문 없이 세션 조회와 같은 JSON을200/no-store로 반환한다. OPEN만 CANCELED로 종료하며 이미 만료된 OPEN은 EXPIRED로 기록한다. CANCELED/EXPIRED의 반복 취소는 안전하다. ISSUING/UNKNOWN/COMPLETED/REJECTED는409로 보존한다. complete와 동일한 사용자 lock을 사용하므로 취소가 먼저면 늦은 complete가 발급/청구를 하지 않고, 발급이 먼저면 취소가 덮어쓰지 않는다. 환불·구독 해지와 별개이며 PG 호출/청구 원장 삭제는 없다. 프론트는 확인된 카드창 실패/닫기 뒤만 이 경로를 사용하고, 네트워크 오류나409만으로 완료·미승인을 단정하지 않는다.

사용자 경로는 기존 access JWT Bearer 인증·소유권 검사를 사용한다. refresh 쿠키 경로의 기존 CSRF 정책은 유지한다. 모든 결제 응답은 `Cache-Control: no-store`다. 앱 mutation은8~100자 영문/숫자/`_`/`-`의 `Idempotency-Key`가 필요하다. 같은 키·다른 본문은409다.

| 경로 | 요청·응답 |
|---|---|
| GET `/api/billing/plans` | planId, priceVersion, amount, currency, interval, products, monthlyInsights, testMode, trialDays, consentVersion 목록 |
| GET `/api/billing/subscription` | planId/status, trialEnd, periodStart/End, nextChargeAt, cancelAtPeriodEnd, graceUntil, amount, priceVersion, entitlements |
| GET `/api/billing/payment-methods` | id, maskedNumber, current, status 목록. billingKey 없음 |
| GET `/api/billing/invoices?cursor=...` | `{items,nextCursor}`, 최대20건. cursor는 다음 페이지용 cycle 번호. 금액/기간/청구 상태/최근 시도 상태/paidAt |
| POST `/api/billing/registration-sessions` | `{purpose,planId?,priceVersion?,consentVersion}`. purpose는 START_SUBSCRIPTION 또는 REPLACE_METHOD. 시작은 유료 plan/priceVersion 필수, 교체는 두 필드 생략. 201 `{sessionId,customerKey,state,expiresAt,successUrl,failUrl}` |
| POST `/api/billing/registration-sessions/{id}/complete` | `{authKey,customerKey,state}`. 동일 로그인 사용자·등록 세션·고객키·state·15분 만료 확인. 202 세션 상태 |
| GET `/api/billing/registration-sessions/{id}` | sessionId, status, invoiceId, paymentStatus, errorCode. 카드 등록과 실제 결제 성공을 구분 |
| POST `/api/billing/subscription/cancel` | 선택 `{reasons:[코드],note?}` 또는 기존 `{reason:코드,note?}`. 본문 없이도 해지 예약. DISPATCHING/UNKNOWN/REVIEW_REQUIRED 등 미확정 시409 |
| POST `/api/billing/subscription/resume` | `{consentVersion}`. 기간 내 해지 예약 해제, 유효 수단 확인 |
| POST `/api/billing/invoices/{id}/retry` | 본문 없음. 확정 거절된 미납 청구서만 새 시도 생성, 202 `{invoiceId,status}` |
| POST `/api/billing/webhooks/toss` | 이 경로만 사용자 JWT 예외. 토스 원본 bytes+전송시각의 HMAC 서명 검증 후 BILLING_DELETED를 저장/반영. 재전송·복수 v1 서명 지원, 읽기 전/중64KiB 본문 제한 |

동의 버전은 `monthly-2026-11-v1`이다. 첫 구현에서는 consentVersion과 acceptedAt을 저장한다. 앱 멱등 결과는 중복 실행을 막고 현재 원장 상태를 다시 보여주므로 재조회 시 응답 상태는 진행에 따라 달라질 수 있다.

등록 상태: OPEN / ISSUING / COMPLETED / REJECTED / UNKNOWN / CANCELED / EXPIRED. COMPLETED는 카드 등록 완료이며 paymentStatus가 SUCCEEDED여야 결제 확정이다. 발급 응답 유실 후 billingKey를 확보하지 못하면 재등록이 필요하다. 복귀 URL 방문만으로 권한을 부여하지 않는다.

주요 오류는 BILLING_DISABLED(503), INVALID_INPUT(400), FORBIDDEN(403), TRIAL_ENDED/PAYMENT_FAILED/PRODUCT_LIMIT_EXCEEDED(403), BILLING_IN_PROGRESS/PLAN_PRICE_CHANGED/BILLING_CONSENT_REQUIRED/IDEMPOTENCY_KEY_REUSED/BILLING_SESSION_EXPIRED/BILLING_MERCHANT_CHANGED(409)다.

## 원장과 동시성

V17은7개 신규 테이블을 추가한다. 고객키·TEST 환경·상점은 billing_subscriptions에 통합했다. 기존 테이블/업무 데이터에 backfill·과금·삭제를 수행하지 않는다.

- billing_subscriptions: 계정별 구독·체험·청구 anchor·기본 카드·상점/환경.
- billing_methods: AES-GCM 암호화 빌링키, 별도 HMAC lookup fingerprint, 마스킹 카드. 키를 브라우저에 반환하지 않는다.
- billing_sessions: 목적·가격·동의 snapshot·난수 세션·state hash·발급 멱등키·만료.
- billing_invoices: 계정별 cycle의 금액·통화·주문명·이용기간 snapshot. 재시도도 같은 청구서 유지.
- billing_attempts: 시도별 orderId·멱등키·요청 hash·결과·lease. 기존 실패를 이후 성공으로 수정하지 않는다.
- billing_commands: 앱 mutation의 사용자/작업/멱등키/body hash/결과 참조/동의.
- billing_webhook_inbox: 중복 처리 정보와 fingerprint. 원본 billingKey/본문 저장 없음.

사용자 row lock으로 한 계정의 변경을 직렬화한다. PostgreSQL의 복합 소유권 FK, 청구서별 진행 중 시도1개·성공 시도1개의 partial unique index가 최종 방어선이다. DB transaction 안에서 외부 PG를 호출하지 않는다.

PG 승인 timeout은75초, 시도 lease는180초다. PG 승인 후 DB 저장 실패·timeout·재시작은 기존 orderId 조회로 복구한다. 새 orderId로 우회 승인하지 않는다. 미확정 상태가15일을 넘으면 REVIEW_REQUIRED로 격리하며 자동 처리·새 승인·해지를 차단하고 수동 대조를 요구한다. 미납/수단 부재 계정을 due 대상에서 제외해 뒤의 계정 청구를 막지 않는다. provider 응답의 orderId·금액·통화·상점을 대조한 DONE만 PAID로 반영한다. 알 수 없는 PG 오류는 거절로 추정하지 않는다.

결제 작업기는 기존 AI scheduler와 분리한 단일 daemon executor를 사용한다.1분마다 due 청구서 준비·한 시도 처리·옛 수단 폐기를 실행한다. 다중 앱 인스턴스에서도 claim/lease와 DB 제약으로 중복 승인을 방지한다. 여러 달 중단 후 밀린 월을 한꺼번에 청구하지 않는다.

## 검토 후 보완한 결제 계약

- 해지는 미완료 START_SUBSCRIPTION 세션도 취소한다. 늦은 발급 응답/실패가 취소 상태를 덮어쓰지 않으며, 취소·만료 후 받은 키는 기본 수단으로 사용하지 않는다.
- 기존 유료 구독이 갱신 중이면 START_SUBSCRIPTION을 새로 열 수 없다. 기간이 끝난 해지 구독의 재시작은 가능하지만, 카드 등록만으로 기존 유예/유료 권한을 되살리지 않는다. 첫 결제 승인 후에만 해지 상태를 해제한다.
- 아직 PG를 호출하지 않은 READY 시도는 청구 직전에 최신 기본 카드를 채택한다. DISPATCHING/UNKNOWN 시도는 기존 카드와 주문을 유지한다. 발송 전 수단이 사라졌으면 PG 호출0의 명확한 실패로 남겨 카드 등록 후 수동 재시도할 수 있다.
- 첫 결제/첫 결제 재시도의 READY 대기가15분을 넘으면 청구서와 시도를 VOID/CHECKOUT_EXPIRED로 마감한다. 새 동의를 통한 새 등록을 요구하며 지연된 첫 청구를 자동 실행하지 않는다.
- paidAt은 조회·복구 시각이 아닌 토스 approvedAt이다. 첫 결제의 이용기간·anchor는 실제 승인 시각에서 시작하고, 이후 갱신 anchor는 유지한다. DONE인데 승인 시각이 없거나 주문 생성보다60초 이상 과거/서버 시각보다60초 이상 미래면 UNKNOWN으로 남긴다.
- 주문·상점·금액·통화·paymentKey가 일치하고 approvedAt이 없는 ABORTED 응답은 확정 실패로 처리한다. 조회만으로 자동 재청구하지 않고 명시적 수동 재시도만 허용한다.
- 키 삭제 tombstone을 발급 응답보다 먼저 받은 경우에도 반영한다. PostgreSQL에서는 fingerprint의 transaction advisory lock으로 서로 다른 DB connection의 저장/삭제를 직렬화한다. 사용자 row도 잡는 경로는 user→fingerprint 순서다. H2의 transaction 완료까지 유지하는 JVM lock은 로컬 검증용이며 운영 다중 인스턴스 검증을 대신하지 않는다.
- 기본 수단에도 사용자 소유권 복합 FK를 적용한다. 암호화/lookup 키의 분리는 Base64 표현이 아니라 디코딩한32바이트의 차이로 검사한다.

V17은 미공유·미배포인 로컬 신규 migration이므로 이번 검토에서 보완했다. 이미 적용된 DB의 checksum을 우회하지 않으며, 해당 상태를 발견하면 별도 migration으로 이관해야 한다. 이번에는 폐기용 DB에만 적용했다. 상세 검토·회귀 근거는 [빌링 검토 기록](../verification/billing-ledger-review-2026-11-06.md)을 따른다.

## 설정과 안전한 기본값

| 변수 | 의미 |
|---|---|
| IPGO_BILLING_ENABLED | 기본false. API와 entitlement 검사를 활성화 |
| IPGO_BILLING_WORKER_ENABLED | 기본false. 자동 첫 청구/월 갱신/대조/옛 수단 폐기를 활성화. API ON 필요 |
| IPGO_BILLING_MODE | TEST만 허용. LIVE는 시작 시 거부 |
| IPGO_TOSS_SECRET_KEY | 테스트 API 개별 연동 시크릿. 브라우저/로그/문서에 값 기록 금지 |
| IPGO_TOSS_SECURITY_KEY | 공식 웹훅 HMAC 서명 검증용 |
| IPGO_TOSS_MERCHANT_ID | 동일 테스트 상점 식별자. 기존 원장과 달라지면409로 중단 |
| IPGO_BILLING_ENCRYPTION_KEY | 별도32바이트 Base64 암호화 키 |
| IPGO_BILLING_LOOKUP_KEY | 암호화 키와 다른32바이트 Base64 lookup 키 |
| IPGO_BILLING_RETURN_ORIGIN | 기본 로컬 origin. HTTPS 또는 localhost HTTP만 허용, 임의 복귀 URL 입력 없음 |

키 교체 시 기존 암호문과 대조에 필요한 키를 유지하는 별도 이관이 필요하다. 현재 v1 암호화 키를 임의 교체하면 기존 수단을 복호화할 수 없다. 이 기능을 켜는 작업은 구현 검토 이후 별도로 진행한다. 키가 준비되지 않아도 기본 OFF 상태의 기존 서버는 기동한다.

공통 EngineCalculationService 요청 지점에서도 구독을 확인해 상품 수정·예정 입고 변경을 통한 재계산 우회를 차단한다. 기존 warehouse→user 잠금 순서를 유지한다. 이미 접수된 업로드의 CLEAN 완료와 이어지는 UPLOAD 내부 계산은 기존 작업으로 완료를 허용하며, 이 trigger를 HTTP 입력으로 받지 않는다. 상품 수 검사는 완료 작업의 기간 만료 여부 대신 요금제 한도만 검사한다. 서버는 가입 시각 기반 이용기간을 검사하고 업로드·입고 설정 재계산·직접 시나리오·설명 생성 요청을 제한한다. 상품 자동 등록에서는 사용자 lock을 유지한 채 신규 상품 수와 전체 요금제 한도를 확인한다. 읽기/기존 계산 취소 요청은 이 구독 게이트로 막지 않는다.

## 검증과 다음 단계

```bash
./gradlew test --tests 'com.ipgo.server.billing.*' --tests 'com.ipgo.server.infrastructure.TossBillingGatewayTests'
./scripts/verify-billing-ledger.sh
```

최초 구현 검증(후속 수정 전): H2 관련114건, PostgreSQL 서버 역할의 빌링35건 모두 실패/오류/생략0. bootJar와 diff/shell 구문 검사 통과. 전체 migration SQL·전체30개 테이블(빌링7개 추가)·소유권 FK·partial index·AI 접근 차단을 폐기용 PostgreSQL16에서 확인했다. 기존 Flyway Docker 검증 script의 기대값도17개 migration/30개 table로 갱신했으며, 해당 Docker Flyway script 전체를 별도로 재실행한 결과와는 구분한다.

두 번째 명령은 기존 로컬 postgres:16-alpine 이미지·JDK21·의존성 캐시가 필요하다. 독립 내부 Docker network와 임시 DB만 만들고 종료 시 제거한다. 전체 migration SQL을 적용한 뒤 서버 역할로 같은 테스트를 실행하고 AI 역할 접근·FK·partial index를 검사한다. 토스 API 어댑터는 테스트에서 mock HttpClient 또는 가짜 BillingGateway로 교체된다. 실제 키와 실제 토스 테스트 상점 결제는 사용하지 않는다.

후속 범위는 클라이언트 연결, 실제 테스트 상점/MID·키 종류 확인, 복귀 페이지의 query 로그/Referrer/분석 스크립트 제어, 개발 배포 후 테스트 결제다. 아직 토스의 실제 서명/카드 등록창/결제 응답을 실측한 결과가 아니다.

이번 원장/API 단계는 월 AI100회 사용량의 원자적 예약/집계, 조직·좌석, 요금제 변경/일할 계산, 영수증 링크·부분/과거 청구 환불, 운영 알림/24시간 작업기, 키 rotation 이관을 구현하지 않는다. API는 상품 한도와 AI 한도 정보를 제공하지만 AI 횟수는 아직 집계/차감하지 않는다. 기존 이슈 #110과 클라이언트 #186/#187 등의 전체 완료나 라이브 준비 완료로 확대하지 않는다.

배포 전 승인 SHA·migration·기존 계정의 체험 만료 영향·테스트 결과를 확인한다. 중단 시 신규 API/worker를 끄고 DISPATCHING/UNKNOWN을 조회·대조한다. 이전 앱으로 돌아가도 신규 원장과 이미 승인된 결제는 보존하며, 자동 삭제·자동 환불하지 않는다.

공식 계약: [빌링 연동](https://docs.tosspayments.com/guides/v2/billing/integration), [코어 API](https://docs.tosspayments.com/reference), [인증/멱등키](https://docs.tosspayments.com/reference/using-api/authorization), [웹훅](https://docs.tosspayments.com/reference/using-api/webhook-events), [키 종류](https://docs.tosspayments.com/reference/using-api/api-keys).

## TEST 전액 취소 (Server #171)

- #171의 최초 구현 뒤 #208에서 회원 직접 환불 제한을 추가했다. 현재 정책과 운영자 환급 절차는 [README의 환불 제한](../../README.md#결제-완료-후-회원-직접-환불-제한-208)을 함께 따른다. 서버의 운영자 환불 API나 권한 역할은 없으며 회사 귀책 환급은 운영자가 Toss 관리자 화면에서 처리한다.
- `POST /api/billing/invoices/{id}/cancel-payment`에 Bearer 인증·`Idempotency-Key`·`{reason}`(1~200자)을 보낸다. `IPGO_PAYMENT_REFUND_RESTRICTED=false`인 전환 기간에는 본인 최신 승인 청구의 전액만 취소한다. `true`면 새 회원 환불은 `409 REFUND_NOT_ALLOWED`이며 Toss 취소를 호출하지 않는다. 이미 시작된 환불의 조회·복구는 유지한다. CANCELED이면200, PROCESSING/UNKNOWN/REVIEW_REQUIRED이면202다. 금액을 받지 않으므로 부분 취소로 바꿀 수 없다.
- 취소 전에 원래 orderId로 조회해 paymentKey 대소문자·MID·원주문·KRW·총액을 확인한다. 토스 `/v1/payments/{paymentKey}/cancel`에 cancelReason과 원장에 고정한 provider 멱등키를 전송한다. CANCELED·잔액0·유효한 canceledAt을 확인한 때만 완료한다.
- 승인 billing_attempts/SUCCEEDED와 원래 결제 기간을 보존하고 invoice VOID·별도 billing_refunds/CANCELED·subscription CANCELED로 기록한다. 취소 요청은 다음 자동 청구를 중단하고 확정 취소는 이용 권한을 끝낸다. 환불 중에는 재개·새 등록·탈퇴를 차단한다.
- 응답 유실/DB 반영 실패는 UNKNOWN 또는 처리 lease를 보존해 같은 주문 조회/같은 provider 키로 복구한다. 14일 후에는 REVIEW_REQUIRED로 격리하며 자동 취소를 반복하지 않는다.
- 청구 내역 응답에 refundStatus/canceledAt을 추가한다. 승인 성공과 취소 완료는 별도 상태다.
- V24 회원 탈퇴를 보존하고 V25에 환불 원장을 추가했다. 서버 역할만 SELECT/INSERT/UPDATE 가능하고 DELETE 및 AI 조회는 허용하지 않는다. 기존 Flyway 원본은 수정하지 않는다.
- 공식 취소 계약: https://docs.tosspayments.com/reference#결제-취소 . 토스 TEST 환경 준비·실제 카드 등록/PG 승인·취소 검증은 배포 후 별도로 확인한다.

## 정기결제의 Toss 관리자 취소 확인 (#171, V35)

관리자 화면에서 처리한 전액 취소는 현재 TEST 상점의 최신 유효 PAID 청구만 조회해 발견한다. 회원 환급 API의 신규 요청·권한은 변경하지 않는다. `billing_invoices`의 provider_checked_at/provider_signal_at로 하루 주기·신호 뒤 최소10분·오래 기다린 청구 우선 순서를 유지한다. 서명 없는 신호는 PG 상태·금액을 확정하지 않는다.

정상 전액 취소는 refund CANCELED·invoice VOID·같은 주기 구독 CANCELED/next_charge_at null로 원자 반영한다. 승인 기록·기간·조회 도중 승인된 새 주기는 보존한다. 부분 취소는 REVIEW_REQUIRED이며 이용권과 청구 계획은 유지한다. 다른 상점·탈퇴·과거 주기·만료·기존 환급은 새 발견 대상에서 제외한다. 실제 PG 호출은 조회뿐이며 취소 API를 다시 보내지 않는다.

일반결제 오류가 이 정기결제 확인을 막지 않도록 worker 단계의 오류를 분리한다. 모의 PG·격리DB 성공과 실제 배포/관리자 환급 실검증은 별도이며, V35 적용은 백업·기존 유지보수 gate·정확 SHA 승인 뒤 수행한다.
