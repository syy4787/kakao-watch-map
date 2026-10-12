#!/usr/bin/env bash
# GitHub Secret(RELEASE_KEY_PASSWORD)에 붙여넣은 서명 비밀번호를 정리해서 출력한다.
# 붙여넣다 섞인 공백·줄바꿈, 앞의 '비밀번호:' 글자(파일 내용을 통째로 붙여넣은 경우 포함)를 떼어 낸다.
set -euo pipefail
raw=${RELEASE_KEY_PASSWORD:-}
re='비밀번호[[:space:]]*:[[:space:]]*([^[:space:]]+)'
if [[ $raw =~ $re ]]; then
  raw=${BASH_REMATCH[1]}
fi
printf '%s' "${raw//[[:space:]]/}"
