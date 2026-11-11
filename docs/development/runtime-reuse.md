# 검사한 JAR를 재사용하는 개발 배포

목적은 필수 검사를 유지하면서 테스트→Docker 컴파일→배포 Docker 컴파일의 반복을 줄이는 것이다.
기존 ECS 안정화·정확 후보 완료·앱 HTTP·이미지 digest·설정 보존·환경 승인·복구 검사는 유지한다.
이 변경의 총 10분대 배포 효과는 다음 승인된 실제 배포 전까지 미측정이다.

## 생성과 재사용

1. native ARM `arm-test`에서 같은 Gradle 호출로 `test bootJar`를 실행한다. 테스트 실패 시 뒤 단계는 중단한다.
2. 생성된 bootJar 하나를 `.prepared-runtime/app.jar`로 보관하고 source SHA·원본 저장소·linux/arm64·CI run/attempt·JAR SHA256/크기 manifest를 만든다.
3. `Dockerfile.runtime`으로 이 JAR를 포함한 CI 이미지를 만든다. 기존 JRE·실행 사용자·메모리 옵션을 유지한다.
4. **dev push만**, 성공한 ARM job에서 두 파일을 immutable v4 artifact로 보관한다. PR/fork는 업로드하지 않는다.
   JAR는 이미 압축돼 있어 추가 압축을 생략하며 7일만 보관한다. artifact 업로드/다운로드·GitHub 저장 용량은 무료라고 가정하지 않는다.
5. 기존 source CI gate가 네 필수 job의 최신 성공·동일 SHA/attempt를 검증하고 `approved-ci.json`을 남긴다.
6. 같은 SHA ECR 이미지가 있으면 기존 경로로 그대로 재사용하며 artifact를 다시 받지 않는다.
7. 이미지가 없고 source에 새 runtime 계약이 있는 onebox 배포만 그 CI run의 **현재 attempt** artifact를 받는다.
   GitHub가 반환한 ZIP SHA256과 실제 다운로드, manifest와 JAR SHA256, 크기·파일명·경로·symlink를 검사한다.
8. 통과한 JAR만 재컴파일 없이 runtime 이미지로 포장해 게시한다. 기존 BuildKit cache·SBOM·provenance와 ECR tag/digest 재검증을 유지한다.

artifact는 JAR 재사용이며 컨테이너 전체를 재사용하는 것은 아니다. 최종 runtime 이미지는 기존 절차대로
만들고 검증한다. CI와 배포의 출처를 단순 파일명/자체 manifest만으로 신뢰하지 않는다.

## 실패와 이전 버전

- artifact 만료/없음/중복/출처·SHA·attempt·ZIP/JAR 해시 불일치는 **실패로 중단**한다. 재빌드로 조용히 우회하지 않는다.
- 만료된 artifact가 필요하면 승인 범위에서 해당 source CI를 다시 검증·생성하고 새 attempt 성공을 확인한다. 자동 재실행하지 않는다.
- 새 source 계약이 없는 과거 버전 또는 legacy amd64는 이전 소스 Dockerfile 경로를 유지한다.
  이는 계약 존재 여부로 결정하며 API 오류나 검증 실패를 과거 경로 선택으로 바꾸지 않는다.
- 이미 존재하는 ECR 이미지는 artifact 보존 기간과 무관하게 기존 immutable SHA 이미지 검사 후 재사용할 수 있다.
- CI `container`와 JVM `test`는 데이터 의존성이 없어 병렬로 실행한다. 배포 gate는 여전히 네 job 전체 성공을 요구한다.
  실패한 CI에서도 일부 병렬 작업이 수행될 수 있다. 서비스 교체 자체는 다른 서비스와 병렬화하지 않는다.

## 검증과 시간

```bash
python3 -m unittest discover -s scripts/testing -p 'test_*.py' -v
```

출처/현재 attempt·만료·누락·해시 변조·경로 이탈·symlink·기존 ECR 경로·실패 중단을 검사한다.
로컬 시험과 GitHub artifact 서비스의 실제 전송 성공은 구분한다. 실제 게시/환경 승인은 별도 범위다.
새 artifact 단계와 최종 runtime 포장·전송도 전체 시간에 포함한다. CI가 요청 후 실행됐다면 CI 대기도 포함하고,
이미 존재하던 CI/이미지는 그 출처를 표시한다. 준비·DB·서버·AI·프론트·최종 검증/기록을 모두 포함해 측정한다.
오래된 승인 SHA로 새 workflow 변경을 자동 배포하지 않는다. 게시 전 Issue 연결·PR·검토·CI·새 정확 SHA 승인 절차를 따른다.

근거: [GitHub upload-artifact v4 고정 소스](https://github.com/actions/upload-artifact/tree/ea165f8d65b6e75b540449e92b4886f43607fa02).
