# 정제·계산 실패·재시도·동시성 근거

상태: #95의 검증 대조·보강, **개발 화면 잔여 확인 전 종료 보류**.
기준 Server b66bc8a. 기존 #26/#27/#60의 정상 연결을 다시 구현하지 않는다.
이번 변경은 테스트/문서뿐이며 production API·스키마·설정·작업 정책 변경은 없다.

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
| 실패/대기/실행 C2와 화면 | 기존 자동UI검사 및 실제C2상태기록은 있으나 실패후이전정상화면의새대조근거는부족 | **#95 종료 전 빈조건의 실제 화면 대조가 남음**. 현재운영시간밖에서공유서비스를기동/고의실패/업무쓰기하지않음 |

## 이번 실행 범위

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

## 종료 판단

현재추가production기능이나정책변경이필요하다는결함은이검증에서발견하지않았다. 남은것은위실제화면증거다.
그확인과기존근거연결이끝나기전에는 #95와부모 #26/#27/#60을완료로일괄처리하지않는다.
새실패유도·공유자료변경·기존DLQ재전송은이문서/PR로실행하지않는다. 내일 #96예약은인증전용이며범위를추가하지않는다.
