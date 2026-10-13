#!/usr/bin/env bash
# 앱에 들어갈 유튜브 키가 실제로 되는지 한 번 물어보고 결과를 실행 요약(알림·경고)에 남긴다.
# 키는 빌드와 같은 순서로 고른다: GitHub Secret YOUTUBE_API_KEY → 없으면 암호화 파일의 youtube.api.key.
# 영상 정보 1건만 묻는다(하루 한도 1만 단위 중 1). 안 돼도 0으로 끝낸다 — 앱은 그때 카카오 동영상 검색으로 찾으므로 배포는 그대로 한다.
set -uo pipefail

key=$(printf '%s' "${YOUTUBE_API_KEY:-}" | tr -d '[:space:]')
props=${SECRETS_FILE:-}
if [ -z "$key" ] && [ -n "$props" ] && [ -f "$props" ]; then
  key=$(sed -n 's/^[[:space:]]*youtube\.api\.key[[:space:]]*[=:][[:space:]]*//p' "$props" | tail -n 1 | tr -d '[:space:]')
fi
if [ -z "$key" ]; then
  echo "::notice title=유튜브 키 없음::워치의 노래 검색은 카카오 동영상 검색으로 해요"
  exit 0
fi
# 떼어 낸 뒤의 값은 GitHub 가 자동으로 가리지 못할 수 있어 직접 가린다
echo "::add-mask::$key"

body=$(mktemp)
code=$(curl -sS -o "$body" -w '%{http_code}' --max-time 20 \
  "https://www.googleapis.com/youtube/v3/videos?part=id&id=jNQXAC9IVRw&key=$key") || code=000
reason=$(jq -r '[.error.details[]?.reason, .error.errors[]?.reason] | map(select(. != null)) | .[0] // empty' "$body" 2>/dev/null || true)
rm -f "$body"

if [ "$code" = 200 ]; then
  echo "::notice title=유튜브 키 확인::키가 잘 돼요 — 워치가 유튜브 자체 검색을 써요"
else
  echo "::warning title=유튜브 키 확인::키가 안 돼요 (HTTP $code${reason:+ · $reason}) — 앱은 카카오 검색으로 대신해요. Google Cloud 의 키와 YouTube Data API v3 사용 설정을 확인하세요"
fi
exit 0
