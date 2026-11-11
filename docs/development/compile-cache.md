# 컴파일 산출물 재사용

CI의 x86/ARM Gradle 명령에만 `--build-cache`를 사용한다. 정책은
`gradle/compile-cache.gradle`이며 JavaCompile만 캐시한다. 테스트와 JAR·리소스 등 다른 task는
캐시하지 않는다. 모든 Test는 up-to-date 생략도 거부해 H2·외부 DB 모두 매번 실행한다.
기존 외부 DB 구분·테스트 조건·개수·실제 DB 검증과 정확 SHA의 ARM JAR 검증은 유지한다.

컴파일 캐시 키는 Gradle의 소스/classpath/컴파일러 입력 외에 OS·아키텍처·Java toolchain의
전체 runtime 버전도 구분한다. 기존 setup-java 캐시 역시 OS/아키텍처별이며 PR은 읽기 전용이다.
별도 원격 캐시 서버나 권한은 추가하지 않는다. CI 캐시를 처음 채우는 실행의 비용은 별도다.
기존 Dockerfile의 `COPY gradle gradle`에 정책 파일이 포함되며 Docker/CD 경로는 변경하지 않는다.

로컬에서 동일 환경·소스로 다음을 대조할 수 있다. 캐시 준비 시간은 비교 밖으로 숨기지 않고 별도 기록한다.

```bash
./gradlew --no-daemon --max-workers=2 --build-cache compileJava compileTestJava
./gradlew --no-daemon --max-workers=2 --no-build-cache clean test bootJar
./gradlew --no-daemon --max-workers=2 --build-cache clean test bootJar
```

각 비교는 같은 Java/Gradle/CPU/메모리·의존성 조건에서 수행한다. build 출력은 clean으로 제거하고
개선 실행의 compileJava/compileTestJava FROM-CACHE, test의 실제 실행, JUnit 결과의 동일 개수·
실패/생략 수와 JAR 해시를 확인한다. 반복 실행에서 Test가 UP-TO-DATE/FROM-CACHE가 되는 경우는
통과로 처리하지 않는다. 소스 변경은 컴파일 캐시 miss가 되어야 한다.

로컬 x86 결과를 실제 ARM·Actions·배포 단축으로 확대하지 않는다. 테스트/외부 DB·앱 health·
후보 완료·환경 승인·복구 검사를 줄여 시간을 맞추지 않는다. 활성화 취소는 CI의 --build-cache와
build.gradle의 apply를 함께 되돌리며, 기존 의존성/컴파일 캐시를 삭제할 필요는 없다.
