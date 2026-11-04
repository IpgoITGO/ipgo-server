# AI 결과 압축 수신 계약

2026-11-03: 실제 원장 정제 결과가 SQS wire900,000bytes를 넘는 문제를 해결한다.
작은 결과는 contractVersion1을 유지한다. 큰 결과만 v2 JSON으로 감싼다:
contractVersion=2, encoding=gzip+base64, uncompressedBytes, sha256, payload.
SHA256은 해제한 원본 v1 UTF-8 JSON의 해시다. 인증 수단을 추가한 것은 아니다.
wire900,000bytes/해제16MiB 상한, 유효한 정수 길이·해시·v1 내부 계약을 확인한다.
압축 손상/중첩v2/상한 초과는 도메인 저장 전에 거부하고 기존 재시도/DLQ 경계를 유지한다.
외부 JSON에는 내부 job ID를 복제하지 않으며, 기존 내부 v1 ID·작업유형·상태 검증과
transaction·중복 결과 무시·저장 성공 이후ACK 규칙을 그대로 적용한다.

서버 decoder를 AI publisher보다 먼저 배포해야 한다. AI에서 이전 버전으로 되돌리면
새 큰 원장의 압축 발행은 중단되므로 서버 decoder는 보존한 뒤 queue를 확인한다.
이번 작업은 로컬 코드/격리 테스트이며 운영 배포·DB 변경·실제 LLM 호출은 수행하지 않는다.
Spring BACKTEST job 생성/저장은 별도이며 이번 변경은 수신 압축 경계만 다룬다.
