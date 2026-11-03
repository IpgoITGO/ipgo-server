# DA-07 위험 표본 근거 저장 누락 보완

실제 개발 서버의 합성80SKU 계산에서 위험값·960건 표본·시나리오는 저장됐지만
riskBasis=POOLED가 DB/API에서 null인 현상을 확인했다. EngineResultWriter가 기존 필드를
누락한 것이며 AI 계산·기존 risk_basis 열·API DTO는 이미 이 값을 지원한다.

엔진의 SKU/POOLED/null을 기존 열에 저장하고 알 수 없는 enum은 transaction 안에서 거부한다.
누락/null은 구형 엔진 호환과 미산출을 위해 유지하며 임의로 SKU로 추정하지 않는다.
기존 테스트 수치와 무관한 저장/조회 회귀에서 문제를 재현했고 H2통합10개가 통과했다.
이 후보는 로컬 수정이며 기존 승인 배포 SHA를 대체하거나 DB의 과거 행을 보정하지 않았다.
