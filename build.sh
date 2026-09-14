#!/usr/bin/env bash
# 빌드 후 jar를 지정한 폴더로 복사한다(UPM에 직접 업로드할 수 있게).
#
# 복사 위치는 사람마다 다르므로 저장소에 적지 않는다. 둘 중 하나로 넘긴다:
#   DOWNLOADS=/path/to/dir ./build.sh
#   또는 저장소 루트에 .build.local 을 만들고  DOWNLOADS=/path/to/dir  한 줄
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"
DOWNLOADS="${DOWNLOADS:-}"
[ -f .build.local ] && . ./.build.local
: "${DOWNLOADS:?복사 위치를 DOWNLOADS 환경변수나 .build.local 에 지정해야 한다}"

# 한글 문구는 i18n/*.src 에만 있고 리소스는 생성물이다. 빌드 전에 항상 다시 만든다.
python3 tools/make-i18n.py

/opt/atlassian-plugin-sdk/bin/atlas-mvn -B clean package "$@"

JAR="$(ls -t target/*.jar | grep -v '\-tests' | head -1)"
cp "$JAR" "$DOWNLOADS/"
echo
echo "빌드 완료: $JAR"
echo "복사 완료: $DOWNLOADS/$(basename "$JAR")"
