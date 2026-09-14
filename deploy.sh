#!/usr/bin/env bash
# 실행 중인 Confluence(UPM)에 jar를 업로드한다. atlas-run 은 쓰지 않는다.
#
#   CONFLUENCE_BASE=http://<host>:<port> CONFLUENCE_USER=<admin> CONFLUENCE_PASS='...' ./deploy.sh
#
# 주소·계정은 환경마다 다르므로 저장소에 적지 않는다.
set -euo pipefail

cd "$(dirname "$0")"

BASE="${CONFLUENCE_BASE:?CONFLUENCE_BASE 환경변수에 Confluence 주소를 넣어야 한다}"
USER="${CONFLUENCE_USER:?CONFLUENCE_USER 환경변수에 관리자 계정을 넣어야 한다}"
PASS="${CONFLUENCE_PASS:?CONFLUENCE_PASS 환경변수에 관리자 비밀번호를 넣어야 한다}"
KEY="co.bskim.confluence.attachment-janitor"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}"

# 같은 버전을 다시 설치하면 웹 리소스 URL이 그대로여서 브라우저가 이전 빌드의 JS를 계속
# 쓴다. 배포마다 버전에 빌드 시각을 붙여 URL을 바꾼다.
QUALIFIER=".$(date -u +%Y%m%d%H%M%S)"
python3 tools/make-i18n.py
/opt/atlassian-plugin-sdk/bin/atlas-mvn -B -q clean package -DskipTests \
    "-Daj.build.qualifier=$QUALIFIER"

JAR="$(ls -t target/*.jar | grep -v '\-tests' | head -1)"
echo "업로드 대상: $JAR → $BASE"

# 비밀번호를 -u 로 넘기면 ps 에 그대로 보인다. curl 의 --config 는 표준입력으로 받으므로
# 커맨드라인에 남지 않는다.
aj_curl() {
    printf 'user = "%s:%s"\n' "$USER" "$PASS" | curl -s --config - "$@"
}

# UPM 은 업로드 요청마다 일회성 토큰을 요구한다.
HEADERS="$(aj_curl -I "$BASE/rest/plugins/1.0/?os_authType=basic" 2>/dev/null || true)"
TOKEN="$(printf '%s' "$HEADERS" | tr -d '\r' \
    | awk -F': ' 'tolower($1)=="upm-token"{print $2}')"

if [ -z "$TOKEN" ]; then
    echo "UPM 토큰을 못 받았다. 계정/비밀번호 또는 $BASE 접근을 확인할 것." >&2
    exit 1
fi

aj_curl -H "Accept: application/json" -F "plugin=@$JAR" \
    "$BASE/rest/plugins/1.0/?token=$TOKEN" > /dev/null

echo "업로드 요청 전송 완료. 설치 진행 상태:"
for _ in $(seq 1 40); do
    sleep 2
    STATUS="$(aj_curl "$BASE/rest/plugins/1.0/$KEY-key" 2>/dev/null || true)"
    if echo "$STATUS" | grep -q '"enabled":true'; then
        echo "설치·활성화 완료"
        exit 0
    fi
done
echo "제한 시간 안에 활성화를 확인하지 못했다. UPM 화면에서 상태를 확인할 것." >&2
exit 1
