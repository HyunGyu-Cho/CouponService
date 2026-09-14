#!/usr/bin/env bash
# 세션 시작 검사. Claude Code SessionStart 훅에서 실행된다.
#   1. 세션 시작 시점 커밋을 기록한다 (턴 종료 검사의 기준점)
#   2. git 훅 경로를 .githooks 로 맞춘다 (커밋 검사 자동 설치)
#   3. 지난 세션에서 문서와 대조되지 않은 채 남은 코드 변경이 있으면 알린다
set -u
cd "$(git rev-parse --show-toplevel)"
source scripts/lib.sh

mkdir -p "$STATE_DIR"

# 3번을 먼저: 기준점을 갱신하기 전에 "이전 기준점 이후" 변경을 봐야 한다
prev_base="$(session_base)"
pending="$(changed_since "$prev_base" | filter_code)"
uncommitted="$(git status --porcelain | grep -E '^( M|M |A |\?\?)' | awk '{print $2}' | filter_code)"

git rev-parse HEAD > "$SESSION_HEAD_FILE"

if [ "$(git config core.hooksPath || true)" != ".githooks" ]; then
    git config core.hooksPath .githooks
    echo "[세션 시작] git 커밋 검사를 설치했습니다 (core.hooksPath=.githooks)."
fi

if [ -n "$uncommitted" ]; then
    echo "[세션 시작 경고] 커밋되지 않은 코드 변경이 남아 있습니다. 작업을 시작하기 전에 먼저 처리하세요."
    echo "  대상 파일:"
    echo "$uncommitted" | sed 's/^/    - /'
    echo "  읽어야 할 문서:"
    echo "$uncommitted" | docs_for_code | sed 's/^/    - /'
    echo "  문서와 대조해 어긋난 부분을 고치고, 판단을 남긴 뒤 커밋하세요. 이 변경은 이번 턴 종료 검사에도 걸립니다."
    # 미커밋 변경은 이번 세션의 턴 종료 검사에도 걸리도록 기준점을 HEAD 그대로 둔다 (위에서 이미 HEAD 기록)
fi

echo "[세션 시작] 읽을 문서 순서는 AGENTS.md, 현재 단계는 README '현재 진행 상태' 참고."
exit 0
