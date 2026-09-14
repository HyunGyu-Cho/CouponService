#!/usr/bin/env bash
# Claude Code PreToolUse 훅. git commit 명령이 커밋 검사를 우회하지 못하게 한다.
#   - --no-verify / -n 사용 금지
#   - core.hooksPath 가 .githooks 가 아니면 설치
# 실제 메시지·파일 검사는 git 의 commit-msg 훅(.githooks/commit-msg)이 한다.
set -u
input="$(cat)"
cmd="$(echo "$input" | sed -nE 's/.*"command"[[:space:]]*:[[:space:]]*"((\\.|[^"\\])*)".*/\1/p' | head -1)"

if echo "$cmd" | grep -qE -- '--no-verify|(^|[[:space:]])-n([[:space:]]|$)'; then
    echo "[커밋 차단] --no-verify 로 커밋 검사를 건너뛸 수 없습니다. 검사를 통과시키거나 메시지에 Docs: 줄을 남기세요." >&2
    exit 2
fi

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
if [ "$(git config core.hooksPath || true)" != ".githooks" ]; then
    git config core.hooksPath .githooks
fi
exit 0
