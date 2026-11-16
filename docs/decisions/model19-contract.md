# 고정19일 모델 저장 계약

대상: Server233 / IPGO-261. IPGO_ENGINE_DEFAULT_METHOD 기본은 B이며, LGBM19와 IPGO_ENGINE_MODEL_VERSION을 명시할 때만 새 요청을 만든다. 환경 설정 변경이나 AWS 배포는 별도 단계다.

서버는 소유권이 확인된 활성 상품의 productCodes(서비스ID→바코드)와 고정 modelVersion을 계산 snapshot/outbox payload에 넣는다. 모델 버전도 input_hash에 포함되어 이전 버전의 실행을 재사용하지 않는다. policy_version은 BUFFER100_FLOOR0이며 기존 A/B 입력/결과는 보존한다.

소비자는 LGBM19 결과에 요청과 일치하는 modelVersion·19일·targetStockQty와 미산출 평가/위험을 요구한다. 요청이 B인데 LGBM19 결과가 오는 경우도 거부한다. 버전·목표 재고·모델 fallback은 기존 resultFacts JSON에 저장하며 V9의 method VARCHAR(10)을 사용해 새 DB migration은 필요하지 않다. 정상/권장0/계산불가와 jobId 중복 처리를 유지한다.

Model19FlowIntegrationTests는 opt-in이다. 격리 PostgreSQL, 로컬127.0.0.1 FastAPI, Git에 없는 .model19-validation/identity.json이 있어야 한다. 합성 이력으로 실제 모델 API를 호출하고 Spring 결과 소비·DB저장·조회·중복과 CUSTOM을 확인한다. 운영 데이터·AWS·LLM을 사용하지 않는다.

빈 테스트 PostgreSQL에는 먼저 이 저장소의 database/bootstrap/create_roles.sql을 적용한다. 운영 DB에서 이 테스트/fixture를 실행하지 않는다. 검증에서는 AI service reader의 view SELECT 허용과 업무 테이블 INSERT/UPDATE 거부를 확인했고, 버전이 다른 결과는 저장 전에 거부했다.

배포 준비 순서는 서버의 새 방식 수용과 Client391 표시 호환 확인 → 비공개 모델 AI 이미지 → 승인 후 서버 모델 설정이다. 이번 작업에서 환경 설정은 바꾸지 않는다. 되돌릴 때 기본 방식을 B로 복구하고 이전 이미지/모델을 유지하며 이미 저장된 결과는 삭제/위장하지 않는다.
