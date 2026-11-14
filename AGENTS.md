# Ipgo Server 개발 지침

- 적용 범위: 이 저장소 전체
- 작업 시작 전 연결된 Issue의 목적과 완료 조건을 확인합니다.
- 한 Issue와 PR에는 하나의 작업 목적만 포함합니다.

## 개발 흐름

- 최신 `dev`에서 `feature/<issue-number>-<description>` 브랜치를 만듭니다.
- 구현과 검증 후 `dev` 대상 PR을 생성합니다.
- `main` 변경은 `dev → main` PR로만 진행합니다.
- `main`에 직접 push하거나 force push하지 않습니다.
- `main` PR은 작성자 외 팀원 1명의 승인을 받아야 합니다.

## 앞으로의 인프라 작업 위치

- AWS 서버·네트워크·DB 인스턴스·S3·SQS·DNS·접근 권한·Secret 리소스·백업·관측성과
  공통 운영 정책의 새 작업은 `IpgoITGO/ipgo-infra`에서 정의·구현·검증하고 운영 문서를 관리합니다.
- 이 저장소는 API·업무 로직·Flyway 테이블 변경·Docker image·서버별 배포 설정을 맡습니다.
  예: 파일을 DB에 저장하는 기능은 Server, DB 인스턴스 용량·백업 설정은 Infra 작업입니다.
- 기능과 인프라 변경이 함께 필요하면 레포별 Issue·PR을 나눠 연결하고 선행 조건·적용 순서를
  명시합니다. 기존 작업에 새 인프라 요구가 생기면 그 부분부터 Infra 작업으로 분리합니다.
- 이 기준으로 완료·종료된 작업이나 과거 Issue·PR을 다시 열거나 기존 코드를 옮기지 않습니다.
  실제 배포·권한 변경·Secret 값 주입·비용 발생은 기존 승인 절차를 따릅니다.

## 작성과 검증

- Issue와 PR은 쉬운 한국어로 작성하고 기술 용어는 자연스러운 영어 표기를 유지합니다.
- commit은 `type: short English summary` 형식을 사용합니다.
- PR에는 상세 변경, 충돌 위험도, API·database 영향과 실제 검증 결과를 기록합니다.
- 모든 작업에서 README를 확인하고 동작·구조·실행 방법이 바뀌면 같은 PR에서 갱신합니다.
- 변경 범위에 맞는 검사를 실행하고 결과를 PR에 남깁니다.
- 검사 전에 [준비 확인과 재검사 기준](docs/rules/workflow.md#준비-확인과-재검사-기준)을 적용합니다.
  같은 기준의 필수 검증은 유지하고, 환경 오류의 무작정 재시도와 근거 없는 전체 재검사를 피합니다.
- Linux/Docker 로컬 Gradle 검사는 `bash scripts/testing/local-gradle.sh <기존 검사 인자>`를 사용합니다.
  전용 캐시를 재사용하며 공유 `~/.gradle`을 컨테이너의 쓰기 캐시로 직접 연결하지 않습니다.

## 안전

- 비밀값, 인증정보, 개인정보와 `.env` 내용을 출력하거나 commit하지 않습니다.
- 기존 사용자 변경과 현재 Issue에 무관한 변경을 섞지 않습니다.
- database migration, 외부 공개, 배포, 삭제는 실행 전 영향과 복구 방법을 확인합니다.

## 세부 문서

| 작업 | 문서 |
|---|---|
| branch·Issue·PR 흐름 | [작업 흐름](docs/rules/workflow.md) |
| 문장·commit·Markdown 형식 | [작성 규칙](docs/rules/conventions.md) |
| 비밀값·공유 파일·외부 영향 | [안전 규칙](docs/rules/safety.md) |
