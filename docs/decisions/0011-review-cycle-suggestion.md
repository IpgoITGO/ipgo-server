# 0011. 정제 결과의 입고 주기 제안 (#92)

## 정책

AI 정제 파일 안의 검산을 통과한 SKU별 양수 입고 날짜를 사용한다.
같은 날짜의 여러 LOT를 하나로 세고 SKU마다 입고일 3개 이상(간격 2개 이상)이면
간격 중앙값을 산출한다. 유효 SKU 중앙값들의 중앙값을 0.5 올림하여 양수 정수로
반환하고 표본 부족은 null이다. 서로 다른 SKU의 입고일을 섞지 않는다.
수요 예측의 `isValidObservation`과 물리 입고 검산을 구별하여 첫 입고를 누락하지 않는다.
첫 구현은 파일 내부만 사용한다. 월 경계/누적 추정은 포함하지 않는다.

서버는 같은 창고의 기간별 최신 revision부터 선택한 뒤 DONE만 후보로 남기고
가장 최근 자료 기간의 `validation_report.estimatedReviewCycleDays`를 읽는다.
최신 완료 보고서의 null을 과거 양수 값으로 대체하지 않는다.
처리 중/실패 revision의 같은 기간 이전 값은 사용하지 않는다.
다른 과거 기간은 그 기간의 최신 revision이 DONE이면 후보에 남는다.

## 표시·확정 경계

- S1/P1/K1의 미확정 값은 같은 조회 서비스를 사용한다.
- 상품별 저장값 → 창고 확정값 → 제안값 순서이며 GET은 DB 쓰기·job 생성을 하지 않는다.
- S1 confirmed=false/ESTIMATED는 제안 상태이며 자료 부족/null에도 출처 enum을 추가하지 않는다.
- 사용자가 S2로 확인한 뒤 계산하며, 기존 기본값/상품 override/과거 계산 snapshot을 정제가 바꾸지 않는다.
- 화면은 null을 빈칸으로 표시하고 수동 기본값 변경은 상속 상품에만 반영한다.
  상품별 직접 입력만 기존 override와 함께 S3로 보내며 재조회는 편집 중 입력을 보존한다.
- 미확정 계산 차단은 기존 EngineCalculationService를 유지한다.
- 파일 안 입고일 3개 이상에서 산출되는 제안은 최대 한 달 이하라 기존 366일 계산 범위 안이다.
  기존 수동 조건의 허용 범위를 이번 변경에서 확대하지 않는다.

## 저장·계약·검증

기존 JSONB 보고서를 재사용하며 신규 migration이 없다. 기존 null 보고서는 자동으로
채워지지 않는다. 과거 데이터 일괄 재정제와 V15 DB 원본 전환은 별도 범위다.

`ReviewCycleSuggestionIntegrationTests`는 기간/도착 순서, 최신 revision, null,
처리 중/실패, 사용자 격리, 확정값/override 보존, 보고서 저장·확정 동시 실행,
S1/P1/K1·조회 무쓰기 및 실제 AI 정제 응답 저장을 검사한다.
동시 검사는 보고서 저장과 S2 확인의 DB 동시 실행이며 실제 SQS 장애 시험은 아니다.

`contracts/review-cycle-clean-result.json`은 AI 실제 정제 함수에 합성 XLSX를 넣어
생성했다. A SKU의 5월1/8/15일 입고와 중간 일별 잔고를 사용한 손계산 7일이며,
서버 검사는 해당 JSON을 CleaningResult로 읽고 실제 UploadCleaningService.complete를 실행한다.
네트워크/SQS 또는 실제 사용자 자료를 통한 종단 실행을 의미하지 않는다.

검증 명령:

```bash
./gradlew --offline --no-daemon --max-workers=2 test \
  --tests '*ReviewCycleSuggestionIntegrationTests' \
  --tests '*ReplenishmentIntegrationTests' \
  --tests '*Product*IntegrationTests' \
  --tests '*UploadCleaningIntegrationTests' --rerun-tasks bootJar
```

Java21과 준비된 Gradle cache를 사용한다. 같은 검사를 빈 폐기 PostgreSQL16에도
IPGO_TEST_DB_URL/USERNAME/PASSWORD와 SPRING_FLYWAY_ENABLED=false로 실행한다.
시험 DB는 역할 bootstrap과 V1~16 SQL을 순서대로 적용하며 기존 운영 DB를 연결하지 않는다.
이는 Flyway 이력/배포 검증을 대체하지 않는다. 겹치는 H2/PG 검사를 별도 업무 표본으로 합산하지 않는다.
