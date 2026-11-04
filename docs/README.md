# 개발 문서 안내

필요한 문서만 선택해 읽습니다. 공통 규칙은 한곳에만 기록하고 다른 문서에서 링크로 연결합니다.

| 확인할 내용 | 문서 |
|---|---|
| Issue부터 병합까지의 순서 | [작업 흐름](rules/workflow.md) |
| Issue·PR·commit 작성 방식 | [작성 규칙](rules/conventions.md) |
| 비밀값과 위험한 변경 기준 | [안전 규칙](rules/safety.md) |
| AWS 데이터 접근·서버/AI 역할과 현재 연결 범위 | [결정 0001](decisions/0001-aws-data-access-contract.md) |
| 실제 정제·계산 outbox·재시도·복구 | [엔진 작업 연결](decisions/0003-engine-job-connection.md) |
| ERD·database 역할·migration 변경 방식 | [Database ERD v1](database/erd-v1.md) |
| 정제 결과 저장·U2 조회와 남은 계약 | [#26 구현 범위](decisions/0002-upload-cleaning-boundary.md) |

서버 실행 방법과 주요 구조는 루트 [README](../README.md)에 유지합니다.
