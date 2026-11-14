#!/usr/bin/env bash
# 로컬 전용: 공유 캐시는 읽기 원본이며 컨테이너는 현재 사용자 소유 캐시에만 쓴다.
set -euo pipefail
umask 077
[[ "$(id -u)" != 0 ]] || { echo '로컬 Gradle 검사는 일반 사용자로 실행해주세요.' >&2; exit 1; }
repo=$(pwd -P)
[[ -f "$repo/gradlew" && -f "$repo/gradle/wrapper/gradle-wrapper.properties" ]] || {
    echo '검사할 서버 worktree의 최상위에서 실행해주세요.' >&2; exit 1;
}
[[ $# -gt 0 ]] || { echo '사용법: local-gradle.sh test bootJar (캐시 확인만: --prepare-only)' >&2; exit 1; }
if [[ "$1" == '--prepare-only' ]]; then [[ $# == 1 ]] || exit 1; fi
for arg in "$@"; do
    case "$arg" in
        -g|-g?*|--gradle-user-home|--gradle-user-home=*|-Dgradle.user.home=*)
            echo '전용 캐시 경로는 IPGO_SERVER_GRADLE_HOME으로 지정하세요.' >&2; exit 1 ;;
    esac
done
cache=${IPGO_SERVER_GRADLE_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/ipgo-server/gradle}
seed=${IPGO_SERVER_GRADLE_SEED:-$HOME/.gradle}
dist=$(sed -nE 's@.*(gradle-[0-9][^/ ]*-(bin|all))\.zip.*@\1@p' "$repo/gradle/wrapper/gradle-wrapper.properties")
[[ -n "$dist" && "$dist" != *$'\n'* ]] || { echo 'Gradle wrapper 버전을 확인할 수 없습니다.' >&2; exit 1; }
[[ ! -L "$cache" ]] || { echo '전용 캐시의 심볼릭 링크는 허용하지 않습니다.' >&2; exit 1; }
cache=$(realpath -m "$cache")
seed=$(realpath -m "$seed")
[[ "$cache" != "$seed" && "$cache" != "$seed/"* ]] || { echo '공유 캐시를 쓰기 대상으로 사용할 수 없습니다.' >&2; exit 1; }
[[ ! -L "$cache" ]] || { echo '전용 캐시의 심볼릭 링크는 허용하지 않습니다.' >&2; exit 1; }
mkdir -p "$(dirname "$cache")"
exec 9>"${cache}.lock"
flock 9
temporary=''
trap '[[ -z "$temporary" ]] || rm -rf -- "$temporary"' EXIT
if [[ ! -e "$cache" ]]; then
    [[ -d "$seed/wrapper/dists/$dist" && -d "$seed/caches/modules-2" ]] || {
        echo '오프라인 wrapper·의존성 원본이 없습니다. 기존 승인 경로로 준비해주세요.' >&2; exit 1;
    }
    temporary=$(mktemp -d "${cache}.prepare.XXXXXX")
    mkdir -p "$temporary/wrapper/dists" "$temporary/caches"
    cp -R --reflink=auto -- "$seed/wrapper/dists/$dist" "$temporary/wrapper/dists/"
    cp -R --reflink=auto -- "$seed/caches/modules-2" "$temporary/caches/"
    [[ -z "$(find "$temporary" -type l -print -quit)" ]] || { echo '원본 캐시의 심볼릭 링크를 거부합니다.' >&2; exit 1; }
    # 새로 복사한 내 캐시에만 쓰기 권한을 준다. 공유 원본에는 chmod/chown하지 않는다.
    chmod -R u+rwX -- "$temporary"
    mv -T -- "$temporary" "$cache"
    temporary=''
fi
[[ -d "$cache" ]] || { echo '전용 캐시 경로가 디렉터리가 아닙니다.' >&2; exit 1; }
# 최상위뿐 아니라 daemon·버전별 캐시·내부 파일까지 검사한다.
bad=$(find "$cache" \( -type l -o ! -uid "$(id -u)" -o ! -readable -o ! -writable \
    -o \( -type d ! -executable \) \) -print -quit)
[[ -z "$bad" ]] || { echo '전용 캐시 내부의 소유권·읽기/쓰기 권한이 맞지 않습니다. 공유 캐시로 우회하지 않습니다.' >&2; exit 1; }
[[ -d "$cache/wrapper/dists/$dist" && -d "$cache/caches/modules-2" ]] || {
    echo '현재 wrapper·오프라인 의존성을 전용 캐시에 준비해주세요.' >&2; exit 1;
}
if [[ "${1:-}" == '--prepare-only' ]]; then
    printf '%s\n' "$cache"
    exit 0
fi
image=$(docker image inspect eclipse-temurin:21-jdk-jammy --format '{{.Id}}')
flock -u 9
exec docker run --rm --network none --user "$(id -u):$(id -g)" \
    -e HOME=/tmp -e GRADLE_USER_HOME=/gradle \
    -v "$repo:/workspace" -v "$cache:/gradle" -w /workspace "$image" \
    ./gradlew --offline --no-daemon "$@"
