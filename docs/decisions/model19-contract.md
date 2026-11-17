# 고정19일 모델 저장 계약

대상: Server233 / IPGO-261. IPGO_ENGINE_DEFAULT_METHOD 기본은 B이며, LGBM19와 IPGO_ENGINE_MODEL_VERSION을 명시할 때만 새 요청을 만든다. 환경 설정 변경이나 AWS 배포는 별도 단계다.

서버는 소유권이 확인된 활성 상품의 productCodes(서비스ID→바코드)와 고정 modelVersion을 계산 snapshot/outbox payload에 넣는다. 모델 버전도 input_hash에 포함되어 이전 버전의 실행을 재사용하지 않는다. policy_version은 BUFFER100_FLOOR0이며 기존 A/B 입력/결과는 보존한다.

소비자는 LGBM19 결과에 요청과 일치하는 modelVersion·19일·targetStockQty와 미산출 평가/위험을 요구한다. 요청이 B인데 LGBM19 결과가 오는 경우도 거부한다. 버전·목표 재고·모델 fallback은 기존 resultFacts JSON에 저장하며 V9의 method VARCHAR(10)을 사용해 새 DB migration은 필요하지 않다. 정상/권장0/계산불가와 jobId 중복 처리를 유지한다.

Model19FlowIntegrationTests는 opt-in이다. 격리 PostgreSQL, 로컬127.0.0.1 FastAPI, Git에 없는 .model19-validation/identity.json이 있어야 한다. 합성 이력으로 실제 모델 API를 호출하고 Spring 결과 소비·DB저장·조회·중복과 CUSTOM을 확인한다. 운영 데이터·AWS·LLM을 사용하지 않는다.

빈 테스트 PostgreSQL에는 먼저 이 저장소의 database/bootstrap/create_roles.sql을 적용한다. 운영 DB에서 이 테스트/fixture를 실행하지 않는다. 검증에서는 AI service reader의 view SELECT 허용과 업무 테이블 INSERT/UPDATE 거부를 확인했고, 버전이 다른 결과는 저장 전에 거부했다.

배포 준비 순서는 서버의 새 방식 수용과 Client391 표시 호환 확인 → 비공개 모델 AI 이미지 → 승인 후 서버 모델 설정이다. 이번 작업에서 환경 설정은 바꾸지 않는다. 되돌릴 때 기본 방식을 B로 복구하고 이전 이미지/모델을 유지하며 이미 저장된 결과는 삭제/위장하지 않는다.

## 개발 환경 활성화와 복구

K1 재고·출고 추이는 계산 입력과 같은 월별 최신 revision 중 DONE 파일만 조회한다.
최신 revision이 FAILED/PROCESSING이면 이전 DONE으로 되돌아가지 않는다. 기본 조회 종료일과
현재·전년 추이에 같은 파일 집합을 적용한다. 덮어쓴 원본과 과거 계산은 삭제하지 않는다.

새 계산은 전체 월별 최신 파일의 ID·상태를 `selection_snapshot.sourceRevisions`와 입력 hash에 보존한다.
실제 엔진 요청과 `run_source_files`에는 DONE 파일만 넣는다. CUSTOM은 보존한 전체 ID·상태와
현재 자료가 같고, DONE 소스가 원래 요청/관계 테이블과 일치할 때만 실행한다. 계산 전부터 존재해
제외했던 과거 실패는 허용하며 이후 새 revision·상태 변경은 저장 직전에도 재검사한다.
이 메타데이터가 없는 과거 snapshot은 기존 보수적인 검사로 처리한다. 원본/과거 결과를 고치지 않고
새 계산부터 적용하며 DB migration이나 공개 API 필드 추가는 없다.

`Deploy server to AWS dev`의 `engine_default_method`는 기본 `preserve`로 현재 설정을 유지한다.
실제 모델을 포함한 AI 이미지가 먼저 배포·검증된 뒤 `LGBM19`와 그 이미지의 정확한
`engine_model_version`(`lgbm19-` 뒤 16자리 소문자 hex)을 함께 입력한다. 엔진 연결 활성화도 필요하다.
이 값은 신규 계산에 전역 적용되며 고객의 lead/review 설정과 과거 결과를 변경하지 않는다.
고정 5+14일 조건이나 매핑·이력이 부족하면 계산 불가이며 B로 자동 대체하지 않는다.

방식을 바꾸는 배포는 현재 workflow와 동일한 source SHA만 허용한다. 구버전 렌더러가 새 입력을 무시하는 일을 막는다.
롤백은 Server를 `B`, 빈 버전으로 먼저 재배포하고 이전 AI 이미지로 복원한다.
저장된 LGBM19 결과와 snapshot은 보존한다. 코드 병합·렌더 검사 성공과 실제 개발 배포·API 검증은 구분한다.

## 일시적인 연결 수용 계측 (기본 OFF)

`ipgo.engine.acceptance-timing-enabled` 기본값은 `false`다. 승인된 개발환경 시험에서만 일시 활성화하며 종료 후 기존 task로 복구한다. `server_calculation_timing` schemaVersion 1 JSON은 UUID jobId·양의 계산 runId만 상관값으로 사용한다. `dispatch` durationMs는 SQS send 호출 경과이고 성공·실패를 구분한다. `result_transaction` durationMs는 결과 수신 처리 진입부터 transaction completion까지의 시간으로 파싱·검증·잠금·DB 저장·commit 또는 rollback을 포함한다. 순수 DB 쿼리 시간이나 큐 대기 시간으로 해석하지 않는다. Spring afterCompletion에서만 committed/rolled_back/unknown을 기록하고 SUCCEEDED/FAILED/DUPLICATE 처리를 분리한다. 비활성·잘못된 jobId·CALCULATE 외 작업은 기록하지 않으며 payload, 사용자·상품 식별자, 큐 주소/receipt 및 예외 메시지를 기록하지 않는다. SQS 재전송 및 결과 중복은 각각의 기록이다. 시간 대조는 단일 프로세스 monotonic duration을 쓰고 다른 서버의 벽시계를 임의로 차감하지 않는다. 실제 측정·배포는 별도 승인 이후에만 진행한다.

개발 배포 workflow의 `acceptance_timing_enabled` 입력은 기본 `false`이며 task의 `IPGO_ENGINE_ACCEPTANCE_TIMING_ENABLED`로 전달된다. 승인된 단기 시험만 `true`로 지정하고 시험 후 승인된 복구 실행은 다시 `false`로 지정한다. 다른 환경 변수·Secret 참조는 기존 renderer 정책대로 보존한다. 실제 workflow 실행은 별도 승인이다.
