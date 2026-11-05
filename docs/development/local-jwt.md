# 로컬 JWT 설정과 접근 절차

DB 연결·JPA 초기화 뒤 `IPGO_JWT_SECRET에는 32바이트 이상의 무작위 키를 Base64로 설정해야 합니다`로 종료되면 실행 프로세스에 JWT 키가 전달됐는지 확인합니다. 이 오류는 DB 접속 실패를 뜻하지 않습니다.

2026-11-05 사용자 결정으로 **독립 로컬 PostgreSQL + 로컬 JWT 키**를 기본 개발 방식으로 사용합니다.
공유 개발 DB는 제한된 조회 또는 범위를 정한 통합 시험에 사용합니다. DB를 공유한다는 이유만으로
JWT 키까지 같아야 하는 것은 아닙니다. 현재 refresh/login 세션도 DB에 저장되므로 로컬 키만
바꾸어도 공유 DB의 업무 데이터나 인증 세션까지 격리되지는 않습니다.

## 사용할 키 선택

- 독립된 로컬 DB: 로컬 전용 무작위 키를 생성합니다. 공유 개발/운영 키를 가져올 필요가 없습니다. 같은 로컬 DB의 로그인 세션을 유지하려면 재시작 때 같은 로컬 키를 안전하게 주입합니다.
- 공유 개발 환경과 같은 JWT를 검증해야 하는 별도 통합 시험: 개발 배포 환경의 승인 담당자에게 목적·대상 DB·사용 기간·안전한 전달 경로를 명시해 접근을 요청합니다. 이는 기본 로컬 실행의 선행조건이 아닙니다. 일반 채팅/이슈/PR에 키를 붙이지 않습니다.
- 운영 키 조회·교체, IAM 확대, 공유 DB migration은 이 안내의 범위가 아닙니다. 독립 로컬 키를 공유 개발 서버 설정에 덮어쓰지 않습니다.

## 공유 개발 키의 관리 위치 확인

승인된 공식 AWS MCP로 계정·리전과 실제 ECS 서비스의 실행 task definition을 읽습니다. 해당 앱 컨테이너의 `secrets`에서 `name=IPGO_JWT_SECRET`인 항목의 `valueFrom`을 확인합니다. 이 값은 Secrets Manager/SSM 참조이며 키 자체가 아닙니다. 최신 task override와 일반 `environment`가 대신 값을 지정하는지도 대조합니다.

2026-11-05 개발 Server TD19의 메타데이터 확인에서는 Secrets Manager 참조였고 일반 환경변수에는 키가 없었습니다. 정확한 내부 참조는 로컬 운영 기록으로 관리합니다. 이 확인은 키 값 조회·개발자 접근 권한·공유 승인을 증명하지 않습니다. 읽기 역할에 비밀값 조회 권한을 추가하지 않습니다.

## 터미널 실행

Java 21, OpenSSL, Python 3를 준비합니다. DB 환경변수·초기 역할·migration 준비는
[README의 빌드와 실행](../../README.md#빌드와-실행)을 먼저 확인합니다. 아래 명령은
저장소 루트에서 실행하며 **독립 로컬 DB**용 키를 생성합니다. `set -x`를 끄고 값을
출력하거나 명령 인자에 넣지 않습니다.

```bash
(
set +x
IPGO_JWT_SECRET="$(openssl rand -base64 32)" || exit 1
export IPGO_JWT_SECRET
python3 - <<'PY' || exit 1
import base64, os
try:
    raw = base64.b64decode(os.environ.get('IPGO_JWT_SECRET', ''), validate=True)
    if len(raw) < 32:
        raise ValueError
except ValueError:
    raise SystemExit('JWT 키 형식 또는 길이가 올바르지 않습니다.')
print('JWT 키 형식·길이 확인 완료')
PY
# 승인된 로컬 DB 설정을 같은 프로세스 환경에 주입한 뒤 실행합니다.
./gradlew bootRun
)
```

괄호 안은 별도 셸입니다. 생성한 키는 앱과 그 하위 프로세스에 전달되며, 별도 셸이 끝나면 원래 터미널 환경에 남지 않습니다. 재시작 로그인 유지가 필요하면 운영체제의 비밀 관리 기능이나 권한이 `0600`인 사용자 전용 파일을 사용하고 그 위치를 Git에서 제외합니다. 위 생성 명령을 매번 실행하면 키가 바뀝니다.

승인받은 기존 키를 입력할 때는 셸 기록에 실제 값을 적지 않습니다.

```bash
(
set +x
read -r -s -p '승인받은 JWT 키: ' IPGO_JWT_SECRET || exit 1
printf '\n'
export IPGO_JWT_SECRET
./gradlew bootRun
)
```

## IDE 실행

IDE가 시작하는 Java/Gradle 프로세스의 환경변수에 DB 설정과 `IPGO_JWT_SECRET`을 지정합니다. 터미널에서 export한 값이 이미 실행 중인 IDE에 자동 전파되지는 않습니다. `.env` 파일만 생성해도 Spring이 자동으로 읽지 않습니다. 사용 중인 IDE/플러그인이 그 파일을 읽는지 확인하고, 없으면 실행 프로세스의 환경변수로 주입합니다. 비밀값이 들어간 공유 실행구성 파일은 커밋하지 않습니다.

## 확인과 오류 해결

1. 승인된 로컬 PostgreSQL에 연결됐는지, 필요한 스키마가 준비됐는지 확인합니다. 공유 DB의 migration을 실행해 확인하지 않습니다.
2. 같은 프로세스에서 위 형식 검사를 실행합니다. Base64 문자열 길이가 아니라 **디코딩 결과32바이트 이상**이어야 합니다.
3. 브라우저로 로컬 HTTP 인증을 시험할 때만 `IPGO_AUTH_COOKIE_SECURE=false`를 실행 환경에 지정합니다. 배포 환경은 HTTPS와 기본값 `true`를 유지합니다.
4. 앱의 `Started ...`와 실제 `/actuator/health`의 HTTP200/UP을 확인합니다. Gradle의 BUILD SUCCESSFUL만으로 서버 기동을 판단하지 않습니다.
5. 키 누락·잘못된Base64·짧은키는 기동 오류로 구분하고 값을 로그에 출력하지 않습니다. IDE에서는 다른 실행구성/Gradle daemon에 값이 전달됐는지도 확인합니다.

읽기 전용 DB 계정은 데이터 조사용입니다. 가입·로그인·업무 저장이 필요한 로컬 앱의 실행 계정으로
대신 사용하지 않습니다. 공유 DB의 쓰기 권한이나 migration 자격 증명을 기본 로컬 실행에 가져오지 않습니다.

## 확인된 실행 결과

2026-11-05 Server `b66bc8a` 기준 사용자 소유 NAS의 격리 환경에서 Java21 앱과 PostgreSQL16으로
검증했습니다. 로컬 DB에 V1~V15를 적용한 뒤 앱 role로 연결하고 runtime Flyway는 껐습니다.
공유 개발 DB·키·권한은 변경하지 않았습니다.

| 로컬 키 설정 | 실제 결과 |
|---|---|
| 누락 / 잘못된 Base64 / 디코딩 후 짧은 키 | 각각 DB 연결·JPA 초기화 뒤 기대 JWT 설정 오류로 종료 |
| 임시 무작위32바이트 키 | 앱 Started 및 실제 HTTP200/UP |

`bootJar`와 인증 회귀13건도 통과했습니다. 테스트에서 시계를 제어한 만료 검사는 실제 서비스의
자연 만료 실측과 구분합니다. 이 결과는 해당 NAS의 실제 로컬 기동 근거이며 다른 담당자 Mac/IDE에서
실행한 것으로 기록하지 않습니다. 공유 키 전달이나 다른 PC 지원이 필요하면 별도 확인합니다.
