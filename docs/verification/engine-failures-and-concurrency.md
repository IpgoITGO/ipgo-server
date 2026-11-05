# 정제·계산 실패·재시도·동시성 근거

상태: **로컬 회귀·실제 개발 실패 화면 검증 완료** (2026-11-05).
1차 로컬 기준은 Server b66bc8a, 후속 실제 개발 검증은 Server320014f/TD20·Clientffcf8e8이다.
기존 #26/#27/#60의 정상 연결을 다시 구현하지 않으며 이 문서 변경은 API·스키마·런타임 동작을 바꾸지 않는다.

## 수용 조건과 근거

| 조건 | 기존/신규 근거 | 출처와 한계 |
|---|---|---|
| 업로드와 outbox 원자성 | uploadAndOutboxCommitOrRollbackTogether, cleanAndCalculationEnqueueRollbackTogetherOnOutboxFailure | 실제 로컬Spring transaction·H2/PG 저장/rollback. 개발DB에 오류를 주입한 시험 아님 |
| 발송 실패·같은 작업 재시도 | leaseCrashAndResendKeepIdentity, EngineTransportFailureTests의 failedDispatch... | 로컬DB job/attempt와 모의SQS adapter fault. 실제AWS 발송실패를 새로 유도하지 않음 |
| 결과 중복 전달 | cleanResultIsAtomicAndLateFailureCannotOverwrite, compressedResultIsAtomicIdempotentAndCorruptionCannotWrite | 중복/늦은실패·압축손상을 실제로컬DB에서 거부/보존 |
| 저장 실패→동일 결과 재처리 | 신규 resultStorageFailureCanRetryTheSameMessageWithoutPartialOrDuplicateRows | scenario 저장 제약 오류를 주입해부분행0·PENDING/QUEUED 보존, 제약 제거뒤 동일메시지DONE·중복0. 합성DB검사 |
| commit 전ACK 방지/ACK 실패 | 신규 EngineTransportFailureTests의 failedTransactionalResult.../ackFailure... | SQS interface 모형의 실패주입·호출순서 검증. 실제AWS ACK실패/재전달 실측과 구분 |
| 계산/정제별 시간 정책 | EngineJobsJdbcTests16 + calculationResendsAfterFiveMinutesAndExpiresAfterFifteenButCleanKeepsLongerWindow | 실제JDBC 인자/SQL경계, CALCULATE5분/15분·CLEAN20분/2시간. 제어시각/합성row이며 자연대기표본 아님 |
| 취소/만료 후 늦은 결과 | cancelStopsResendAndIgnoresLateResult, failureAndExpiryAreTerminal | run/job 종결·재전송차단·늦은결과무시·이전결과보존 |
| 같은입력/이전실패 재사용 | unchangedInputReusesAutomaticRun, restoringEarlierInputCreatesNewRunInsteadOfReusingHistory, failedLatestRunDoesNotReuseAnOlderSuccessfulRun | 최신상태/입력 hash 기준. 과거성공을최신run으로되살리지않음 |
| 설정·발주 변경과 결과 저장 겹침 | 신규 concurrentSettingsAndReceiptChangeKeepOldAndNewCalculationSnapshotsSeparate | 별도결과처리스레드와warehouse잠금안의단위/발주변경·새run생성. 이전입력불변·구run DONE/새run QUEUED·새run결과0 |
| 소유권·범위·잘못된결과 | outOfSelectionAndOtherOwnerResultsStillRollback, calculationSnapshotIsOwnerScopedAndImmutable | 다른회원/상품범위오류거부·입력사본보존. 실제고객별권한시험 전수 아님 |
| 최신revision 실패/처리중 | latestProcessingOrFailedRevisionDoesNotFallBackToOldDone | 이전DONE 파일로최신실패/처리중을가리지않음 |
| 실제 정상업무/C2/화면 | #102/#105 완료기록: 실제6개월 정제·계산과저장·정확도화면, 원래실패/DLQ보존 | 과거실측을이번새실행으로세지않음. 정상성공을실패화면검증으로확대하지않음 |
| 현행 SQS C2와 화면 | 실제 run88의 QUEUED→FAILED·사유와 이전 DONE run28·동일 조건/결과 보존 대조 | 사용자 승인 전용 합성 계정의 실제 개발 API/UI·15분 자연 만료. RUNNING을 관측했다고 기록하지 않음 |

## 1차 로컬 실행 범위 (PR125)

- EngineRealFlowIntegrationTests18(신규2포함)·EngineJobsJdbcTests16·EngineResultTransportTests3:
  **동일37개를 H2와 PostgreSQL16.11에서 각각 통과**, 실패/오류/생략0.
- PostgreSQL은 포트공개 없는 작업전용internal Docker network·폐기용DB다. 테스트 입력/회원/파일은합성이며
  개발업무자료·실제SQS메시지/DLQ·LLM은사용하거나수정하지않았다. 컨테이너/네트워크부재를확인했다.
- 신규SQS adapter fault3개는별도 네트워크차단 단위검사다. 첫실행2통과/1실패는 Mockito 모형이
  SDK Consumer를실행하지않은구성오류였다. 원본을보존하고SDK처럼request Consumer를실행하도록고쳤다.
  실제서비스장애나원래PG37개실패로집계하지않는다. 보정후3건 모두통과·실패/오류/생략0을확인했다.
- 실행식별은JUnit의class/method와H2/PG/adapter 실행군으로남긴다. 각fixture의임시run/job ID는
  공유업무실행ID가아니며폐기DB정리후실제운영행으로재조회할수없다.

README/코드가같다는이유로과거검증을현재운영성공으로바꾸지않는다. 이번테스트로37개x2를74개고유업무사례로
집계하거나모의ACK실패를실제AWS재전달성공으로표시하지않는다.

## C2 상태 계약 결정

- 상태·결정일: 확정, 2026-11-05. 결정 주체는 서버 업무 인수자인 EHWIYA 사용자이며 Codex가 지시를 기록·반영했다.
- 선택: 현행 SQS 경로의 새 계산은 응답 전까지 QUEUED, 응답 또는 서버 만료 때 DONE/FAILED다.
  RUNNING은 기존 응답과 UI의 호환 수용 값이며 현행 작업기가 실행 시작 이벤트를 발행한다는 뜻이 아니다.
- 대안: 실제 AI 실행 시작을 알리는 진행 이벤트를 추가해 RUNNING으로 전환한다.
  사용자가 현행 경로를 계약으로 확정했으므로 이번 검증을 위해 진행 이벤트 기능을 추가하지 않는다.
- 이유·영향: 실제 관측 가능한 대기/종결 상태와 문서의 기대를 맞춘다. Server C2·Client 계산 추적에 적용하며,
  공개 응답의 기존 허용 상태/필드를 삭제하거나 RUNNING 합성 검사를 실제 관측으로 바꾸지 않는다.
- 클라이언트의 5분 대기 한도 초과는 서버 FAILED가 아니다. 상태 재조회로 서버의 최종 결과를 확인한다.

## 후속 실제 개발 검증

사용자가 11/5 23:30 KST까지의 개발 예외 가동과 전용 시험을 승인했다. Production·실제 유료 LLM은 제외했다.

1. 작업/결과 큐가 비어 있고 과거 결과 DLQ가36건임을 확인했다. AI 서비스의 이전 TD9·desired1을 보존했다.
2. 개발 AI 서비스를 약15분 정지하고 전용 합성 계정 user8/warehouse9에서 기존 세 조건값을 그대로 보내
   강제 재계산을 **1회** 요청했다. 새 run88, 이전 정상 run28이며 입력 시각은22:06:05 KST다.
3. 실제 API의 QUEUED와 대기 화면을 확인했다. 브라우저 대기 한도 초과 때 서버는 여전히 QUEUED였으며
   이 상태를 서버 오류로 집계하지 않았다. 시계/토큰/응답을 위조하거나 DB의 작업 시각을 바꾸지 않았다.
4. run88이 **908.9초 후 FAILED**로 종결됐고 실제 errorMessage
   `계산이 오래 걸려 멈췄어요. 다시 계산해 주세요.`와 실패 화면이 일치했다.
   이전 run28의 DONE·대시보드 결과/원본 참조·조건값 digest가 보존됐다.
5. AI TD9·desired1·HEALTHY로 복구한 뒤 작업/결과 큐0·과거 DLQ36 유지, 늦은 결과 처리 뒤에도
   run88 FAILED·run28 DONE과 같은 결과/조건 digest를 다시 확인했다. 원본이나 과거 실패를 수정/삭제하지 않았다.

관찰 프로세스가 SIGTERM(143)으로 종료된 최초 기록을 보존했다. 같은 run88의 조회만 새 관찰기로 이어갔으며
새 재계산은 만들지 않았다. 중단된 시험 로그인1개는 user8·최초5초 구간·단일행 조건으로만 폐기를 확인했고,
이후 두 관찰 로그인은 각각 logout204로 정리했다. 비밀값·쿠키 원문을 문서/로그/커밋에 남기지 않았다.

## 완료 범위와 한계

#95의 빈 검증 조건은 위 1차 로컬 근거와 후속 실제 API/UI·복구 후 보존 대조에 연결했다.
로컬37개×2를74개 고유 실제업무 사례로 집계하지 않고, 모의 SQS ACK 실패를 실제 AWS ACK 장애로 바꾸지 않는다.
실제 시험도 합성 계정의 통제된 서비스 정지1건이며 고객 업무 효과나 모든 장애의 보증이 아니다.
기존 RUNNING 화면 검사는 합성 출처이며 현행 SQS 경로의 실제 RUNNING 관측은 없다.
상위 #26/#27/#60과 #90~#93 등 다른 작업을 일괄 완료 처리하지 않는다. 검증의 최신 종료 기록은 Server #95에 연결한다.
