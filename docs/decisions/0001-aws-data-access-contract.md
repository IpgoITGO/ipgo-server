# 0001. AWS 데이터 접근과 계산 엔진 연동 계약

- 상태: 결정, 구현 전
- 날짜: 2026-10-29
- 관련 Issue: #26, #27, `ipgo-ai` #15

## 배경

Spring Boot와 FastAPI가 같은 업무 데이터를 사용하지만 두 서비스가 모두 데이터를 변경하면
상태와 계산 결과의 정본이 모호해집니다. 긴 정제·계산 작업을 HTTP 요청 안에서 기다리는 방식도
재시도와 장애 복구에 불리합니다.

## 결정

### Database

Spring Boot가 업무 DB와 상태 변경을 소유합니다. 실제 자격 증명은 역할별로 분리합니다.

| 역할 | 허용 | 금지 |
|---|---|---|
| Flyway migration role | schema 생성·변경 | 애플리케이션 런타임 사용 |
| Spring application role | 업무 데이터 `SELECT`·`INSERT`·`UPDATE`·`DELETE` | 임의 schema 변경 |
| FastAPI reader role | 승인된 계산용 view와 필요한 table의 `SELECT` | `INSERT`·`UPDATE`·`DELETE`·DDL |

FastAPI에는 내부 table 전체가 아니라 계산에 필요한 안정된 view를 우선 제공합니다. view에는 유효한
업로드와 source file, 상품과 재고관리단위, 일별 재고, 입고 조건, 기존 발주, 계산 재현용 source를
포함합니다. 정확한 view 이름과 column은 후속 database migration Issue에서 확정하고 FastAPI schema와
함께 검증합니다.

### 원본 파일

- AWS에서는 업로드 원본을 private S3 bucket에 저장합니다.
- Spring task role은 원본을 읽고 쓸 수 있고 FastAPI task role은 읽기만 할 수 있습니다.
- 삭제와 bucket lifecycle 변경 권한은 두 애플리케이션 role에 주지 않습니다.
- 정제 작업 1건은 업로드 파일 1개를 대상으로 합니다. Spring은 DB에 저장한 `storageKey`를 작업에
  연결하고 FastAPI는 같은 key의 객체를 읽습니다.

현재 로컬 디스크 보관 구현은 S3 adapter가 병합되기 전까지 개발 환경에서 유지합니다. 이 결정만으로
현재 저장 방식이나 환경 변수가 바뀌지는 않습니다.

### 정제·계산 작업

- 정제·계산·백테스트처럼 오래 걸리는 작업은 SQS job queue로 요청하고 result queue로 결과를
  전달합니다.
- 시나리오와 AI 인사이트처럼 짧은 작업만 private network의 내부 HTTP로 호출합니다.
- queue message에는 원본 파일, DB 자격 증명, 개인정보, 장기 presigned URL을 넣지 않습니다. 작업과
  source를 식별하는 ID, `storageKey`, 계약 버전만 전달합니다.
- Spring이 유효한 source file을 선택하고 작업 상태를 `PROCESSING`으로 기록합니다. FastAPI는 S3와
  읽기 전용 DB view를 조회해 처리한 뒤 결과를 발행합니다.
- Spring만 결과를 검증하고 업무 DB에 저장하며 상태를 `DONE` 또는 `FAILED`로 바꿉니다.
- 재시도와 중복 전달에 대비해 `jobId` 또는 `runId`를 멱등성 기준으로 사용합니다. 최종 message schema와
  상태 전이는 #26·#27 구현 PR에서 테스트와 함께 확정합니다.

업로드 사이 월 경계 검산이 필요하면 Spring이 현재 유효한 이전 source 식별자를 작업에 포함하고,
FastAPI는 승인된 view에서 이전 기말 재고를 읽습니다. FastAPI가 임의로 최신 업로드를 선택하지 않습니다.

## 구현 순서

1. Spring migration에서 계산용 view와 역할별 grant를 추가합니다.
2. 로컬 보관 interface에 S3 adapter를 추가합니다.
3. #26에서 정제 job/result와 상태 전이를 구현합니다.
4. #27에서 계산 job/result, 실행 기록과 멱등 처리를 구현합니다.
5. `ipgo-ai` worker와 내부 HTTP service를 연결한 뒤 통합 테스트합니다.

이 문서는 목표 계약을 확정합니다. 현재 애플리케이션 코드, DB schema, AWS 자원은 변경하지 않습니다.
