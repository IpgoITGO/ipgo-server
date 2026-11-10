# D0 전용 합성 자료 준비와 OIDC

관련: IPGO-173 / Issue #153, Infra IPGO-174 / Issue #42. 구현과 실제 적용은 구분한다.
기본 상태는 비활성이다. 이 PR은 IAM·개발 DB·GitHub environment를 변경하거나 D0를 실행하지 않는다.
기존 조회 역할, Client 6c515b993b52b65b5c59da9dcacd6011288420c9, Server TD32, AI TD19을 유지한다.

## 경로와 권한

`d0-validation.yml`의 PR 및 dev push job은 로컬 Python·폐기 PostgreSQL·컨테이너 검사만 한다.
수동 dev 실행만 별도 `d0-validation` environment와 새 `ipgo-dev-d0-runner-oidc`를 사용한다.
승인자 1명 이상, 자기승인 금지, custom branch policy `dev` 하나를 실제 GitHub API로 읽고
서명·날짜·SHA를 확인한 뒤에만 15분 OIDC 세션을 얻는다. 정책 조회 실패도 중단이다.
OIDC는 브라우저의 관리자 MCP 인증과 독립적이며 GitHub 환경 승인은 계속 필요하다.

새 역할은 정확한 runtime task revision 1개 실행, 개발 서비스 2개 조회, task 상태 조회,
D0 태그 task 중단, 전용 로그 읽기와 전용 execution role 전달만 허용한다.
일상 컨테이너에는 AWS task role이 없다. ECS agent가 새 Secret에서 제한 DB 로그인만 주입한다.
임의 shell/URL/SQL, SSM, ECS Exec, 서비스 배포, master Secret 읽기는 일상 역할에 없다.
IAM은 subnet/SG 조건으로 RunTask를 제한하지 못한다. 서명된 입력과 고정 프로그램으로 이를 확인한다.

## 적용 전 필요한 별도 승인

1. Server·Infra PR 검토/병합 및 고정 Server SHA의 ECR 전용 이미지 게시.
2. Infra 독립 state 실제 plan 검토. 초기 8개 생성 시 `runtime_enabled=false`로 일상 역할을 만들지 않는다.
3. 고정 install revision의 최초 설치. master 로그인은 installer 컨테이너에만 주입한다.
4. DB 거부 검증 후 일상 역할 2개 리소스 추가와 전용 environment/설정 등록, `read-only` 검증.
5. **D0 `prepare`는 다시 실제 날짜·대상·영향을 확인하고 별도 승인한다.**

Infra 설정·복구는 해당 저장소 `docs/d0-oidc.md`를 따른다. 소스 병합만으로 적용하지 않는다.
현재 flags/Secrets/environment는 이 PR에서 등록하지 않는다.

## 서명 입력

`contract.py`의 `KEYS`/검증 함수가 정본이다. 봉투는 `{manifest, signature}` JSON의 Base64다.
manifest는 scope `ipgo-d0-receipts-v1`, UUID operationId, kind `install/read-only/prepare`, 실제 KST d0Date와
다음날 d1Date, dbHost/dbName, fixtureSha256, keyFingerprint, clientSha/serverTd/aiTd,
accountId, workflowSha/helperSha, imageUri(digest), taskDefinitionArn(revision), subnetIds/securityGroupIds를 담는다.
알 수 없는 필드는 거부한다. 월·연도 경계 D0/D1은 별도 설계가 필요해 중단한다.
일상 실행은 workflowSha=helperSha=승인 Server 전체 SHA, 설치는 workflowSha=Infra SHA/helperSha=Server SHA다.

NAS에서 실제 날짜와 승인 대상만 넣은 로컬 manifest를 만들고 다음 도구로 서명한다.

```bash
python scripts/d0/sign_manifest.py --manifest /protected/manifest.json \
  --private-key /protected/existing-private.pem --output /protected/signed.json
```

의존성은 `scripts/d0/requirements.txt`를 설치한다. private key를 GitHub/AWS/저장소로 옮기지 않는다.
이미지의 공개키와 일치하는 RSA 3072bit 이상 키만 사용한다. 테스트는 별도 일회 키를 사용한다.
서명 도구가 deterministic XLSX hash와 공개키 fingerprint를 채운다. 출력은 0600 신규 파일이다.
`D0_SIGNED_MANIFEST`에는 봉투를 Base64로 넣고 `D0_RUNTIME_JSON`에는 Infra runtime output의 value만 넣는다.
날짜가 지나면 새 operationId/manifest로 승인받는다. 과거 createdAt 조작이나 자동 재시도는 없다.

## DB 경계와 D0 영향

`database/bootstrap/create_d0_ops.sql`은 Flyway 자동 migration에 포함하지 않는 최초 설치 전용이다.
새 NOLOGIN owner와 bootstrap 역할, `d0_ops` schema, operations 원장과 함수 3개를 추가한다.
owner는 users의 계정 생성용 5컬럼 INSERT/제한 SELECT/sequence와 검증용 업무 테이블 SELECT만 갖는다.
owner도 password/email SELECT 권한은 없다. RDS와 비슷한 CREATEROLE 관리자 검증을 포함한다. 설치 시 관리자 자신의 새 owner 및 기존 migrator membership의 SET/INHERIT만 트랜잭션 안에서 잠시 켰다가 원래 옵션으로 복원한다. bootstrap은 CONNECT/USAGE/세 함수 EXECUTE만 가진다.
고정 search_path/UTC와 qualified SQL을 사용한다. PUBLIC 실행권한을 취소하고 기존 역할 권한은 바꾸지 않는다.
기존 PUBLIC grant 등으로 bootstrap에 직접 접근 권한이 생기면 effective privilege 검사에서 중단한다.

`prepare` 영향은 새 합성 사용자 1명, 과거 수불부 한 월·한 SKU 업로드 1개와 정제, 다음날 입고예정
80/40/100 세 건이다. 기본조건은 미확정, 계산/run/LLM은 0이어야 한다. 이것은 LLM 금액/횟수 cap을
다시 설정한다는 뜻이 아니다. 기존 real·지속 LLM·월 비용 제한 OFF·시작 시각은 변경하지 않는다.
서버 응답과 실제 DB의 소유 자료/원장 및 users·warehouses·products·upload_files·calculation_runs·calculation_results·ai_explanations·planned_receipts의 기존 ID 범위 hash를 확인하며 API 쓰기는 재전송하지 않는다.
정제 비동기 읽기만 제한 시간 내 polling한다. 전역 대기 작업·insight가 있으면 시작하지 않는다.

함수는 scope/실제 하루당 새 계정 1개, operation UUID idempotency, 단계 순서, 완료 시 실제 소유 행을
검사한다. 동일 operation 재입력은 계정·발주를 만들지 않고 기존 상태를 돌려준다.
실패·응답 유실 시 추가 업무 요청을 중단하고 원장·이미 생성한 자료를 보존한다. 확장검증/삭제는 없다.

## 설치 실패와 복구

installer는 기존 D0 역할이나 스키마가 하나라도 있으면 재설치/비밀번호 갱신을 거부한다. 새 Secret Put 성공 후에만
새 bootstrap 로그인을 활성화하며 TLS verify-full 제한 로그인 probe를 수행한다. 직접 users/password 조회·UPDATE·DELETE·public CREATE·기존 Server role 전환은 실제 InsufficientPrivilege 거부를 확인하며, 예상 밖 허용도 rollback 후 실패시킨다.
활성화 실패/응답 유실/probe 실패 시 새 bootstrap만 NOLOGIN으로 되돌리기를 시도한다.
DB 연결까지 유실되면 비활성화 성공을 보장할 수 없다. `INSTALL_DEACTIVATION_UNCONFIRMED`로 중단하고
별도 관리자 확인을 받아 새 로그인 비활성화 여부를 확인한다. 기존 계정/역할은 초기화하지 않는다.
SQL은 트랜잭션이지만 Secret과 DB는 원자적이지 않다. Secret/NOLOGIN 객체는 보존하고 자동 설치 재시도를 하지 않는다.

runtime 실패 시 자신이 생성한 task ARN만 중단하며 REQUESTED/STARTED receipt와 안전한 결과 artifact를 남긴다.
RunTask 응답 유실로 ARN을 모르면 성공·미실행을 추정하지 않는다. operation/tag와 CloudTrail로 승인 후 조사한다.
암호문 자격증명은 고정 공개키 OAEP로 봉인하며 평문 password/email을 로그에 남기지 않는다.
GitHub artifact/CloudWatch/DB 원장의 암호문도 검증 자료로 취급하고 삭제하지 않는다.

긴급 중단은 `D0_ENABLED=false`, 새 task 중단, Infra `runtime_enabled=false` 전용 revoke plan으로
새 runtime OIDC role/policy만 제거한다. 새 DB role NOLOGIN은 별도 DB 적용 승인이다.
`enabled=false`나 destroy로 설치 객체·Secret·자료를 지우지 않는다. 복구가 자료 삭제를 의미하지 않는다.

## 검증과 한계

```bash
python -m unittest discover -s scripts/d0 -p 'test_*.py' -v
bash scripts/testing/verify-d0-ops.sh
docker build -f Dockerfile.d0 -t ipgo-d0:local .
```

폐기 PostgreSQL은 내부 Docker network/전용 label만 사용하고 실제 DB DSN을 받지 않는다.
Python fake API/SDK 검증은 실제 AWS IAM·네트워크·Secret·LLM·실제 입고 UI 검증을 대신하지 않는다.
로컬 ImageId도 ECR registry digest와 다르다. 실제 권한 허용/거부 probe, 읽기 workflow와 D0 업무 결과는
별도 적용 승인 뒤 기록한다. 브라우저 MFA/환경 승인이 완전히 사라졌다고 주장하지 않는다.

## B1-2 선행 보완과 복구 경계

서명 manifest와 실행 직전 서비스 검사는 같은 상수 Server TD32·AI TD19를 사용한다.
Client `6c515b9` 기준도 유지한다. 31/17·다른 revision·잘못된 서명은 RunTask 전에 거부한다.
설치는 신규 두 role/`d0_ops`가 모두 없을 때만 허용한다. NOLOGIN인 과거/부분 설치도 자동 재설치하지 않는다.
설치 전체는 session advisory lock으로 보호하고 SQL transaction 안에서도 충돌을 다시 검사하며, 기존 migrator 관리 권한이 없으면 rollback한다.
관리자 membership은 grantor별로 자신의 임시 행만 복원/제거하므로 다른 grantor의 권한을 수정하지 않는다.

설치 전후 관리자 권한으로 10개 업무표의 count/hash를 비교한다. 읽기용 migrator membership은
rollback transaction에서만 사용하고 부족하면 DDL 전에 중단한다. 조회 대상 행이나 hash는 일반 로그에 출력하지 않는다.
설치 전·SQL commit 후·권한 probe 후의 실제 결과가 같을 때만 existingRowsUnchanged를 반환한다.
`preflight(NULL).oldRowsUnchanged=true`만으로 기존 행 불변을 증명하지 않는다. 업무의 동시 변경도 실패로 처리할 수 있으며,
이 비교만으로 외부 동시 트랜잭션의 원인이나 모든 시점 불변을 보장하지 않는다.

신규 bootstrap role에는 설치 operationId의 비밀이 아닌 marker를 남긴다. 설치 실패 시 같은 marker의
role만 NOLOGIN으로 commit한 뒤 해당 role의 세션만 종료하고 0건/NOLOGIN을 확인한다.
세션 종료 권한은 DB 관리자의 실효 권한으로 사전 확인하며 기존 role에 권한을 자동 부여하지 않는다.
`INSTALL_DEACTIVATION_UNCONFIRMED`는 성공이 아니다. 기존 계정·다른 설치 operation·기존 업무 세션은 대상이 아니다.
Secret Put/SQL commit/LOGIN은 비원자적이며, 실패 후 신규 객체·Secret version은 보존한다.

Task 성공 뒤 로그/receipt/digest 검증·결과 저장이 실패하면 installer 예외 처리로 차단됐다고 가정하지 않는다.
`D0_INSTALL_RECOVERY_REQUIRED`에서 멈추고 task 영수증/상태를 확정한 뒤 **별도 승인으로** Infra 고정 install TD에
`operation=deactivate`, `confirmation=DEACTIVATE_D0`를 사용한다. kind=deactivate·동일 설치 operationId·현재 KST 날짜로
새 서명 봉투가 필요하다. 기존 install 서명을 재사용할 수 없다. 정확 SHA/helper/IAM fingerprint/environment/서명/network 검사는 유지한다.
이 복구만 Client HTTP·서비스 건강 검사를 생략해 앱 장애가 로그인 차단을 방해하지 않게 한다.
일상 runtime workflow는 deactivate/install을 허용하지 않으며 추가 IAM 정책은 없다.
RunTask 응답 유실은 REQUESTED/clientToken·operation/tag로 기존 task를 확인·중단한 후에 처리하고 자동 재전송하지 않는다.

검증은 실제 운영 DB와 구분한다. `verify-d0-ops.sh`는 내부 Docker network·폐기 Unix socket만 사용한다.
PG16에서 신규 객체 충돌/transaction rollback·비-superuser membership 복원·실제 행 hash·권한 거부·
신규 로그인 세션 종료/다른 Server 세션 보존을 검사한다. Secret API는 mock이며 TLS 인수만 확인한 probe를
로컬 socket으로 대체한다. 실제 RDS verify-full TLS·SecretsManager·OIDC·Task 등록/복구 성공을 뜻하지 않는다.
