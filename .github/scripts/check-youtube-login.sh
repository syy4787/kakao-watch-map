#!/usr/bin/env bash
# 앱에 들어갈 유튜브 로그인 클라이언트(구글 OAuth 'TV 및 입력 제한 기기')가 실제로 되는지 확인해 실행 요약에 남긴다.
# 사람이 할 일은 없다: 코드 받기(device/code) 한 번 + 그 코드로 토큰을 한 번 물어본다 —
#   'authorization_pending'(아직 아무도 허용 안 함)이 오면 클라이언트 ID·비밀이 둘 다 맞는 것.
#   받은 코드는 쓰지 않고 버린다 (30분 뒤 저절로 없어짐).
# 값은 빌드와 같은 순서로 고른다: GitHub Secret(YOUTUBE_OAUTH_CLIENT_ID/_SECRET) → 암호화 파일(youtube.oauth.client.id/.secret).
# 값·코드는 로그에서 가린다. 안 돼도 0으로 끝낸다 — 앱은 그때 '유튜브 로그인은 준비 중'으로 보여 주고 검색·재생은 그대로 된다.
set -uo pipefail

props=${SECRETS_FILE:-}
prop() {
  [ -n "$props" ] && [ -f "$props" ] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" "$props" | tail -n 1 | tr -d '[:space:]'
}

id=$(printf '%s' "${YOUTUBE_OAUTH_CLIENT_ID:-}" | tr -d '[:space:]')
secret=$(printf '%s' "${YOUTUBE_OAUTH_CLIENT_SECRET:-}" | tr -d '[:space:]')
[ -n "$id" ] || id=$(prop 'youtube\.oauth\.client\.id')
[ -n "$secret" ] || secret=$(prop 'youtube\.oauth\.client\.secret')

if [ -z "$id" ]; then
  echo "::notice title=유튜브 로그인 없음::로그인 클라이언트가 아직 없어요 — 워치 앱은 '유튜브 로그인은 준비 중'으로 보여요 (검색·재생은 돼요)"
  exit 0
fi
echo "::add-mask::$id"
if [ -z "$secret" ]; then
  echo "::warning title=유튜브 로그인 확인::클라이언트 ID 는 있는데 비밀(YOUTUBE_OAUTH_CLIENT_SECRET)이 없어요 — 워치에서 허용해도 로그인이 끝나지 않아요"
  exit 0
fi
echo "::add-mask::$secret"

SCOPE="https://www.googleapis.com/auth/youtube.readonly"
# 시험에서만 바꾼다 (가짜 서버)
BASE=${GOOGLE_OAUTH_BASE:-https://oauth2.googleapis.com}
body=$(mktemp)
trap 'rm -f "$body"' EXIT

code=$(curl -sS -o "$body" -w '%{http_code}' --max-time 20 \
  --data-urlencode "client_id=$id" --data-urlencode "scope=$SCOPE" \
  "$BASE/device/code") || code=000
if [ "$code" != 200 ]; then
  err=$(jq -r '.error // empty' "$body" 2>/dev/null || true)
  echo "::warning title=유튜브 로그인 확인::코드를 받지 못했어요 (HTTP $code${err:+ · $err}) — Google Cloud 의 클라이언트 종류가 'TV 및 입력 제한 기기'인지, 같은 프로젝트에 YouTube Data API v3 가 켜져 있는지 확인하세요"
  exit 0
fi
dc=$(jq -r '.device_code // empty' "$body" 2>/dev/null || true)
uc=$(jq -r '.user_code // empty' "$body" 2>/dev/null || true)
[ -n "$dc" ] && echo "::add-mask::$dc"
[ -n "$uc" ] && echo "::add-mask::$uc"
if [ -z "$dc" ]; then
  echo "::warning title=유튜브 로그인 확인::코드 응답이 예상과 달라요"
  exit 0
fi

code=$(curl -sS -o "$body" -w '%{http_code}' --max-time 20 \
  --data-urlencode "client_id=$id" --data-urlencode "client_secret=$secret" --data-urlencode "device_code=$dc" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:device_code" \
  "$BASE/token") || code=000
err=$(jq -r '.error // empty' "$body" 2>/dev/null || true)

case "$err" in
  authorization_pending|slow_down)
    echo "::notice title=유튜브 로그인 확인::로그인 클라이언트가 잘 돼요 — 워치의 유튜브 › 로그인에서 구글 계정으로 로그인할 수 있어요" ;;
  invalid_client)
    echo "::warning title=유튜브 로그인 확인::클라이언트 비밀이 맞지 않거나 클라이언트 종류가 'TV 및 입력 제한 기기'가 아니에요 (invalid_client)" ;;
  org_internal)
    echo "::warning title=유튜브 로그인 확인::OAuth 동의 화면이 '내부'로 되어 있어요 — '외부'로 바꿔야 개인 구글 계정으로 로그인할 수 있어요 (org_internal)" ;;
  *)
    echo "::warning title=유튜브 로그인 확인::토큰 확인 결과가 예상과 달라요 (HTTP $code${err:+ · $err})" ;;
esac
exit 0
